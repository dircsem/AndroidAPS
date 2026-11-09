package app.aaps.pump.common.hw.medlink.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.core.utils.pump.ThreadUtil
import app.aaps.pump.common.hw.medlink.MedLinkConst
import app.aaps.pump.common.hw.medlink.MedLinkUtil
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.ble.command.BleCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleConnectCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleBolusCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleStopCommand
import app.aaps.core.interfaces.pump.StopOrigin
import app.aaps.pump.common.hw.medlink.bolus.BolusAction
import app.aaps.pump.common.hw.medlink.bolus.BolusOrigin
import app.aaps.pump.common.hw.medlink.bolus.BolusState
import app.aaps.pump.common.hw.medlink.bolus.BolusStatusSnapshot
import app.aaps.pump.common.hw.medlink.bolus.BolusTracker
import app.aaps.pump.common.hw.medlink.bolus.LoopBolusMerge
import app.aaps.pump.common.hw.medlink.bolus.PumpAnswerState
import app.aaps.pump.common.hw.medlink.bolus.PumpBlock
import app.aaps.pump.common.hw.medlink.bolus.PumpLockRule
import app.aaps.pump.common.hw.medlink.bolus.StartRetryRule
import app.aaps.pump.common.hw.medlink.bolus.StartStopSkipRule
import app.aaps.pump.common.hw.medlink.bolus.StopDecision
import app.aaps.pump.common.hw.medlink.bolus.StopGate
import app.aaps.pump.common.hw.medlink.ble.data.BolusMedLinkMessage
import app.aaps.pump.common.hw.medlink.ble.data.CommandStructure
import app.aaps.pump.common.hw.medlink.ble.data.GattAttributes
import app.aaps.pump.common.hw.medlink.ble.data.MedLinkPumpMessage
import app.aaps.pump.common.hw.medlink.ble.operations.BLECommOperation
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkError
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.pump.common.hw.medlink.keys.MedLinkIntentPreferenceKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkStringKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkStringPreferenceKey
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperationResult
import org.apache.commons.lang3.StringUtils
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Stream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.HashSet

/**
 * Created by Dirceu on 30/09/20.
 */
@SuppressLint("MissingPermission")
@Singleton
class MedLinkBLE //extends RileyLinkBLE
@Inject constructor(
    private val context: Context,
    val resourceHelper: ResourceHelper,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
) {

    private class CommandQueueBusy
    constructor(val busy: Boolean) {

    }
    private var lastStatus: MedLinkPumpRunningState = MedLinkPumpRunningState.Unknow
    private var lastStatusSet: Long = 0

    /** T13: when the last StopStartPump (A + start/stop) was written. Cached states older than this are stale. */
    private var lastStartStopSent: Long = 0

    /** T9: the one bolus in flight. Only `M` / `A` answers resolve it. */
    val bolusTracker = BolusTracker(clock = { System.currentTimeMillis() }) { aapsLogger.info(LTag.PUMPBTCOMM, it) }.also {
        it.onConfirmedWithoutEffect = { what -> onCommandWithoutEffect(what) }
        it.onPumpEffect = { what -> onCommandEffect(what) }
        it.onProgress = { r, delivered -> reportBolusProgress(r, delivered) }
    }

    /** Builds an `M` (BolusStatus) message; set by the pump plugin. */
    var bolusStatusMessageProvider: (() -> MedLinkPumpMessage<*, *>)? = null

    /** T8: shows an alarm to the user; set by the pump plugin. */
    var bolusAlarmHandler: ((String) -> Unit)? = null

    /** The pump became locked (null = not locked any more). Called on the BLE handler thread. */
    var pumpBlockHandler: ((PumpBlock?) -> Unit)? = null

    /** [PumpBlock.PUMP_LOCKED] while start/stop/bolus commands are confirmed and do nothing, null otherwise. */
    @Volatile
    var pumpBlock: PumpBlock? = null
        private set

    /** START/STOP/bolus confirmed by the MedLink without effect on the pump, in a row (see [PumpLockRule]). */
    @Volatile
    private var confirmedWithoutEffect = 0

    /** When the watchdog last asked `M` for an unresolved bolus. */
    private var lastBlockStatusRequest = 0L

    /** `M` answered "invalid command" in a row; the connection is reset at [M_INVALID_RESET]. */
    private var mInvalidInARow = 0
    private var lastExternalBolusAlarm: Long? = null
    private val bolusWatchdog = Runnable { runBolusWatchdog() }

    /**
     * Records a bolus the tracker resolved with `M` (amount, pump time, bolus info of the request).
     * Uses the pump bolus time, so a second sighting of the same pump bolus is deduplicated by temporary id.
     */
    var bolusRecorder: ((Double, Long, app.aaps.core.interfaces.pump.DetailedBolusInfo?) -> Unit)? = null

    /** Automatic stop held (bolus in progress) until this time. */
    private var stopHeldUntil = 0L
    private var stopGateAlarmed = false
    private var pendingStatusAt = Long.MAX_VALUE
    private var trackedBolusExecutor: CommandExecutor<*>? = null

    /** T14: guards [mergedBolusParts] and the moment a queued bolus becomes tracked (no merge after that). */
    private val bolusMergeLock = Any()

    /** T14: loop boluses folded into a queued loop bolus, in queue order; the first is the executor's own. */
    private val mergedBolusParts = HashMap<CommandExecutor<*>, MutableList<BleBolusCommand>>()

    private var baselineRequestedFor: CommandExecutor<*>? = null
    private var baselineRequests = 0

    private var lastCommand: MedLinkCommandType = MedLinkCommandType.NoCommand
    private var bug133: Int = 0
    var needToCheckOnHold: Boolean = false
    private var calibrateCommand: CommandExecutor<*>? = null
    private var bluetoothGattCallback: BluetoothGattCallback? = null
    private var firmwareVersion = ""
    private var batteryLevel = 0
    var isCommandConfirmed = false
        private set

    private val bleHandler: Handler
    private var needRetry = false
    private var lastCloseAction = 0L
    private var lastExecutedCommand: Long = 0
    private var isDiscovering = false
    private var sleepSize: Long = 0
    private var lastConnection: Long = 0
    private var connectionStatus = ConnectionStatus.CLOSED
    private var notificationEnabled = false
    private var lastConfirmedCommand = 0L
    private var lastReceivedCharacteristic: Long = 0
    private val handlerThread = HandlerThread("BleThread")
    private val characteristicThread = HandlerThread("CharacteristicThread")
    private val handler: Handler
    private var lastGattConnection = 1L
    private var connectionStatusChange: Long = 0

    //    private var lastPumpStatus: PumpRunningState? = null
    private var manualDisconnect = false

    // private val toBeRemoved: CommandExecutor<Any,out BleCommand>? = null
    private lateinit var radioResponseCountNotified: Runnable
    private val gattDebugEnabled = true
    private var mIsConnected = false
    private var pumpResponse: StringBuffer? = null
    private var gattConnected = false
    private var medLinkDevice: BluetoothDevice? = null
    fun partialCommand(): Boolean {
        return lastConfirmedCommand > lastConnection &&
                lastReceivedCharacteristic > lastConfirmedCommand
    }

    fun removeFirstCommand(force: Boolean) {
        if (currentCommand != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, currentCommand.toString())
        } else {
            aapsLogger.info(LTag.PUMPBTCOMM, "null")
        }
        if (currentCommand != null) {
            synchronized(currentCommand!!) {
                if (currentCommand != null && currentCommand!!.hasFinished() || force) {
                    aapsLogger.info(LTag.PUMPBTCOMM, "hasfinihded " + currentCommand?.hasFinished())
                    aapsLogger.info(LTag.PUMPBTCOMM, "force " + force)
                    currentCommand?.let {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "" + lowPriorityExecutionCommandQueue.remove(it)
                        )
                    }
                    currentCommand?.let {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "" + executionCommandQueue.remove(it)
                        )
                    }
                    currentCommand?.let {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "bolus queue" + bolusExecutionQueue.remove(it)
                        )
                    }
                    currentCommand?.let {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "hash " + bolusExecutionHash.remove(it.firstCommand())
                        )
                    }
                    currentCommand?.let {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "" + priorityExecutionCommandQueue.remove(it)
                        )
                    }
                    currentCommand?.let { onBolusExecutorDropped(it, if (force) "removed (forced)" else "removed") }
                    currentCommand = null
                }
            }
        }
    }

    fun <B> reExecuteCommand(currentCommand: CommandExecutor<B>) {
        aapsLogger.info(LTag.PUMPBTCOMM, "reexecute command")
        if (!hasCommandsToExecute()) {
            val list = currentCommand.commandList.map {
                it.commandPriority =
                    CommandPriority.HIGH
                it
            }.toMutableList()
            addWriteCharacteristic<B, Any>(
                UUID.fromString(GattAttributes.SERVICE_UUID),
                UUID.fromString(GattAttributes.GATT_UUID),
                list, false
            )
        } else {
            currentCommand.clearExecutedCommand()
        }
        nextCommand()
    }

    private fun hasCommandsToExecute(): Boolean {
        return !executionCommandQueue.isEmpty() ||
                !priorityExecutionCommandQueue.isEmpty() ||
                !lowPriorityExecutionCommandQueue.isEmpty() ||
                !bolusExecutionQueue.isEmpty()
    }

    fun post(r: Runnable?) {
        handler.post(r!!)
    }

    fun setFirmwareVersion(s: String) {
        firmwareVersion = s.replace("_", "-")
    }

    fun setBatteryLevel(batteryLevel: Int) {
        this.batteryLevel = batteryLevel
    }

    fun clearExecutedCommand() {
        commandQueueBusy = CommandQueueBusy(false)
        if (currentCommand != null) {
            currentCommand!!.clearExecutedCommand()
        }
    }

    fun setConfirmedCommand(commandConfirmed: Boolean) {
        if (currentCommand != null) {
            currentCommand!!.isConfirmed = true
            if (isBolus(currentCommand!!.getCurrentCommand()) || currentCommand!!.getCurrentCommand() == MedLinkCommandType.BolusAmount) {
                bolusCommandToBeChecked = currentCommand
                // rule 1: a confirmation is not a delivered bolus, schedule the verify M
                // only the amount write commits the dose; the X step is also "confirmed" while still QUEUED
                if (commandConfirmed && currentCommand === trackedBolusExecutor && bolusTracker.current?.state == BolusState.SENT) {
                    reportBolusPhase(currentCommand, app.aaps.pump.common.hw.medlink.bolus.BolusPhase.CONFIRMED)
                    executeBolusAction(bolusTracker.onConfirmed())
                }
            }
        }
        isCommandConfirmed = commandConfirmed

    }

    private enum class ConnectionStatus {
        CLOSED, CLOSING, CONNECTED, CONNECTING,  //        DISCONNECTED,
        DISCONNECTING, DISCOVERING, EXECUTING;

        val isConnecting: Boolean
            get() = this == CONNECTING || this == DISCOVERING || this == DISCONNECTING || this == CLOSING
    }

    private class CommandsToAdd(
        val serviceUUID: UUID,
        val charaUUID: UUID,
        val command: MedLinkPumpMessage<*, *>
    ) {

        override fun toString(): String {
            return "CommandsToAdd{" +
                    "command=" + command.toString() +
                    '}'
        }
    }

    private val notifyingCharacteristics: MutableSet<BluetoothGattCharacteristic> = HashSet()
    private var commandQueueBusy: CommandQueueBusy? = null
    private val MAX_TRIES = 5
    private val BASELINE_MAX_AGE_MS = 30_000L
    private val WATCHDOG_MS = 30_000L
    private val BLOCK_STATUS_RETRY_MS = 60_000L
    private val M_INVALID_RESET = 2
    private val MAX_BASELINE_REQUESTS = 2
    private var isRetrying = false
    private val commandsToAdd = ConcurrentLinkedDeque<CommandsToAdd>()

    private enum class BLEOperationType {
        READ_CHARACTERISTIC_BLOCKING, WRITE_CHARACTERISTIC_BLOCKING, SEND_NOTIFICATION_BLOCKING
    }

    private var bleCommand: BleCommand? = null

    inner class Resp private constructor(
        private val func: Function<Any, MedLinkStandardReturn<*>>,
        private val command: String
    ) {

        fun getFunc(): Function<Any, MedLinkStandardReturn<*>> {
            return func
        }

        override fun toString(): String {
            return "Resp{" +
                    "command='" + command + '\'' +
                    ", func=" + func +
                    '}'
        }
    }

    @JvmField
    @Inject
    var medLinkUtil: MedLinkUtil? = null

    @JvmField
    @Inject
    var medLinkServiceData: MedLinkServiceData? = null
    private var previousLine = ""

    //    private long latestReceivedCommand = 0l;
    private var latestReceivedAnswer = 0L
    private var isConnected: Boolean = false
    fun isConnected() = isConnected
    fun setIsConnected(value: Boolean) {
        isConnected = value
    }

    private var pumpModel: String? = null
    private val addedCommands: MutableSet<String> = HashSet()
    private val bolusExecutionQueue = ConcurrentLinkedDeque<CommandExecutor<*>>()
    private val priorityExecutionCommandQueue = ConcurrentLinkedDeque<CommandExecutor<*>>()
    private val executionCommandQueue = ConcurrentLinkedDeque<CommandExecutor<*>>()
    private val executionCommandHash = HashSet<MedLinkCommandType>()
    private val bolusExecutionHash = HashSet<MedLinkCommandType>()
    private val lowPriorityExecutionCommandQueue = ConcurrentLinkedDeque<CommandExecutor<*>>()
    val onHoldCommandQueue = ConcurrentLinkedDeque<CommandExecutor<*>>()

    private var noResponse = 0

    //    private ConcurrentLinkedDeque<String> executionCommandQueueN = new ConcurrentLinkedDeque<>();
    var currentCommand: CommandExecutor<*>? = null

    var bolusCommandToBeChecked: CommandExecutor<*>? = null
    private var mCurrentOperation: BLECommOperation? = null
    var resultActivity: MutableList<Resp> = ArrayList()
    private var servicesDiscovered = false
    fun getBluetoothAdapter(): BluetoothAdapter? {
        if (context.getSystemService(Context.BLUETOOTH_SERVICE) != null) {
            bluetoothAdapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }
        return bluetoothAdapter
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var lastCharacteristic = ""
    private fun getGattStatusMessage(status: Int): String {
        return if (status == BluetoothGatt.GATT_SUCCESS) {
            "SUCCESS"
        } else if (status == BluetoothGatt.GATT_FAILURE) {
            "FAILED"
        } else if (status ==
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED
        ) {
            "NOT PERMITTED"
        } else if (status == 133) {
            "Found the strange 133 bug"
        } else {
            "UNKNOWN (\$status)"
        }
    }

    private fun removeNotificationCommand() {
        if (!priorityExecutionCommandQueue.isEmpty() &&
            MedLinkCommandType.Notification == priorityExecutionCommandQueue.first!!.getCurrentCommand()
        ) {
            priorityExecutionCommandQueue.pollFirst()
        }
    }

    private fun changeConnectionStatus(newStatus: ConnectionStatus) {
        if (newStatus != connectionStatus) {
            connectionStatus = newStatus
            connectionStatusChange = System.currentTimeMillis()
        }
    }

    //TODO 28/03 removed readcharacteristic from code test to see if it affect the execution
    fun readCharacteristicBlocking(serviceUUID: UUID?, charaUUID: UUID?): BLECommOperationResult {
        aapsLogger.info(LTag.PUMPBTCOMM, "readCharacteristic_blocking")
        val rval = BLECommOperationResult()
        if (bluetoothConnectionGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "readCharacteristic_blocking: not configured!")
            rval.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
        } else {
            if (mCurrentOperation != null) {
                rval.resultCode = BLECommOperationResult.RESULT_BUSY
            } else {
                if (bluetoothConnectionGatt!!.getService(serviceUUID) == null) {
                    // Catch if the service is not supported by the BLE device
                    val services = bluetoothConnectionGatt!!.services
                    rval.resultCode = BLECommOperationResult.RESULT_NONE
                    aapsLogger.error(LTag.PUMPBTCOMM, "BT Device not supported")
                    // TODO: 11/07/2016 UI update for user
                    // xyz rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter);
                } else {
                    val chara = bluetoothConnectionGatt!!.getService(serviceUUID).getCharacteristic(
                        charaUUID
                    )

                    // Check if characteristic is valid
                    return if (chara == null) {
                        aapsLogger.error(
                            LTag.PUMPBTCOMM,
                            "ERROR: Characteristic is 'null', ignoring read request"
                        )
                        rval
                    } else  // Check if this characteristic actually has READ property
                        if (chara.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                            aapsLogger.error(
                                LTag.PUMPBTCOMM,
                                "ERROR: Characteristic cannot be read"
                            )
                            rval
                        } else {
                            // Enqueue the read command now that all checks have been passed
                            if (executionCommandQueue.isEmpty()) {
                                val result = executionCommandQueue.add(object :
                                    CommandExecutor<Any>(
                                        MedLinkCommandType.ReadCharacteristic,
                                        aapsLogger
                                    ) {
                                    override fun run() {
                                        isCommandConfirmed = false
                                        try {
                                            synchronized(commandQueueBusy!!) {
                                                lastExecutedCommand = System.currentTimeMillis()
                                                if (!bluetoothConnectionGatt!!.readCharacteristic(
                                                        chara
                                                    )
                                                ) {
                                                    aapsLogger.info(
                                                        LTag.PUMPBTCOMM,
                                                        String.format(
                                                            "ERROR: readCharacteristic failed for characteristic: %s",
                                                            chara.uuid
                                                        )
                                                    )
                                                    completedCommand()
                                                    if (chara.value != null) {
                                                        aapsLogger.info(
                                                            LTag.PUMPBTCOMM,
                                                            String(
                                                                mCurrentOperation!!.value ?: ByteArray(0),
                                                                StandardCharsets.UTF_8
                                                            )
                                                        )
                                                    }
                                                } else {
                                                    aapsLogger.info(
                                                        LTag.PUMPBTCOMM, String.format(
                                                            "reading characteristic <%s>",
                                                            chara.uuid
                                                        )
                                                    )
                                                    needRetry = true
                                                    //                                        nrTries++;
                                                }
                                                commandQueueBusy = CommandQueueBusy(false)
                                            }
                                        } catch (e: NullPointerException) {
                                            aapsLogger.info(LTag.PUMPBTCOMM, "npe need retry")
                                            needRetry = true
                                        }
                                        super.run()
                                    }
                                })

                                if (result) {
                                    nextCommand()
                                } else {
                                    aapsLogger.error(
                                        LTag.PUMPBTCOMM,
                                        "ERROR: Could not enqueue read characteristic command"
                                    )
                                }
                            }
                            rval

                        }
                }
            }
            mCurrentOperation = null
        }
        return rval
    }

    private fun <B, C> addWriteCharacteristic(
        serviceUUID: UUID, charaUUID: UUID,
        command: MutableList<CommandStructure<B, BleCommand>>, supplementary: Boolean,
    ) {
//        this.latestReceivedCommand = System.currentTimeMillis();
        aapsLogger.info(LTag.PUMPBTCOMM, "commands")
        command[0].command.code.let { aapsLogger.info(LTag.PUMPBTCOMM, it) }
        if (mCurrentOperation != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "busy for the command " + command[0].command.code)
        } else {

            if (isBolus(command[0].command) ||
                command[0].command.isSameCommand(MedLinkCommandType.StopStartPump) ||
                command[0].command.isSameCommand(MedLinkCommandType.CalibrateFrequencyArgument) ||
                executionCommandQueue.none { it.firstCommand() == command[0].command }
            ) {
                var commandExecutor: CommandExecutor<B>
                synchronized(commandQueueBusy!!) {
                    commandExecutor = buildCommandExecutor(charaUUID, serviceUUID, command)
                }
                aapsLogger.info(LTag.PUMPBTCOMM, "adding command" + command[0].command.code)
                if (stopOrigin(commandExecutor) == StopOrigin.USER) {
                    addUserStop(commandExecutor)
                } else if (command[0].commandPriority == CommandPriority.HIGH) {
                    if (command[0].command == MedLinkCommandType.Connect && !isConnected) {
                        priorityExecutionCommandQueue.addFirst(commandExecutor)
                    } else if (command[0].command != MedLinkCommandType.Connect && !priorityExecutionCommandQueue.contains(
                            commandExecutor
                        )
                    ) {
                        if (command.map { it.command }.contains(MedLinkCommandType.Calibrate)) {
                            priorityExecutionCommandQueue.addFirst(commandExecutor)
                        } else if ((lastCommand.isSameCommand(MedLinkCommandType.StartPump) &&
                                    !command[1].command.isSameCommand(MedLinkCommandType.StartPump)) ||
                            command[1].command.isSameCommand(MedLinkCommandType.StartPump) && priorityExecutionCommandQueue.none { it.secondCommand() == MedLinkCommandType.StartPump }
                        ) {
                            priorityExecutionCommandQueue.add(commandExecutor)
                        }
                    }
                } else if (command[0].commandPriority == CommandPriority.NORMAL && (executionCommandQueue.none { it.firstCommand() == commandExecutor.firstCommand() })) {
                    if (command[0].command == MedLinkCommandType.StopStartPump && command[1].command == MedLinkCommandType.StopPump) {
                        // if (bolusExecutionQueue.isNotEmpty()) {
                        //     lowPriorityExecutionCommandQueue.add(commandExecutor)
                        //     // if (isBolus(bolusExecutionQueue.first.getCurrentCommand())) {
                        //     //     bolusExecutionQueue.clear()
                        //     //     bolusExecutionHash.clear()
                        //     // } else {
                        //     //     val first = bolusExecutionQueue.first
                        //     //     bolusExecutionQueue.clear()
                        //     //     bolusExecutionQueue.addFirst(first)
                        //     //     bolusExecutionHash.add(first.getCurrentCommand())
                        //     // }
                        // } else {
                        executionCommandQueue.add(commandExecutor)
                        // }
                    } else if (isBolus(command[0].command) && !bolusExecutionHash.contains(command[0].command)) {
                        bolusExecutionQueue.add(commandExecutor)

                        bolusExecutionHash.add(command[0].command)
                    } else if (supplementary) {
                        aapsLogger.info(
                            LTag.NOTIFICATION,
                            "supplementary command " + commandExecutor.firstCommand()
                        )
                        bolusExecutionQueue.add(commandExecutor)
                    } else if (!isBolus(command[0].command) && executionCommandQueue.filter { f ->
                            f.getCurrentCommand().isSameCommand(commandExecutor.getCurrentCommand())
                        }.isEmpty()) {
                        executionCommandQueue.add(commandExecutor)
                    }

                } else if (lowPriorityExecutionCommandQueue.none { it.firstCommand() == commandExecutor.firstCommand() }) {
                    lowPriorityExecutionCommandQueue.add(commandExecutor)
                }
            }
        }
        return
    }

    /**
     * T0: a user suspend overtakes everything. Boluses not written yet are dropped (the user wants
     * the pump stopped); a bolus already written is left to the tracker (T3 records the partial amount).
     */
    private fun addUserStop(executor: CommandExecutor<*>) {
        val unsent = bolusExecutionQueue.filter { isBolusExecutor(it) && (it !== trackedBolusExecutor || bolusTracker.current?.state == BolusState.QUEUED) }
        unsent.forEach {
            aapsLogger.info(LTag.PUMPBTCOMM, "user stop: dropping queued bolus ${bolusAmount(it)}U")
            it.commandFailed()
            onBolusExecutorDropped(it, "user stop")
            failMergedParts(it, "user stop")
        }
        bolusExecutionQueue.removeAll(unsent.toSet())
        bolusExecutionHash.clear()
        if (unsent.isNotEmpty()) raiseBolusAlarm("Pump suspended by user: ${unsent.mapNotNull { bolusAmount(it) }.joinToString { "$it U" }} queued bolus not delivered")
        executionCommandQueue.removeIf { stopOrigin(it) != null }
        priorityExecutionCommandQueue.removeIf { stopOrigin(it) != null }
        stopHeldUntil = 0L
        priorityExecutionCommandQueue.addFirst(executor)
    }

    private fun <B> buildCommandExecutor(
        charaUUID: UUID, serviceUUID: UUID,
        commands: MutableList<CommandStructure<B,
                BleCommand>>,
    ):
            CommandExecutor<B> {
        // the old continuous M executor (BolusProgressCallback) re-read M in a loop during a bolus: the tracker schedules M now
        return run {
            object : CommandExecutor<B>(commands, aapsLogger) {
                override fun run() {
                    val payload = dataToWrite()
                    if (payload == null) {
                        // never write an empty payload: MedLink answers "invalid command"
                        aapsLogger.info(LTag.PUMPBTCOMM, "nothing to write for ${firstCommand()}, not writing: $this")
                        commandQueueBusy = CommandQueueBusy(false)
                        return
                    }
                    isCommandConfirmed = false
                    if (System.currentTimeMillis() - lastExecutedCommand < 2000) {
                        SystemClock.sleep(2200 - (System.currentTimeMillis() - lastExecutedCommand))
                    }
                    lastExecutedCommand = System.currentTimeMillis()
                    if (bluetoothConnectionGatt != null && bluetoothConnectionGatt!!.getService(
                            serviceUUID
                        ) != null
                    ) {
                        val chara = bluetoothConnectionGatt!!.getService(serviceUUID)
                            .getCharacteristic(charaUUID)
                        chara.value = payload
                        //                                    chara.setWriteType(PROPERTY_WRITE); //TODO validate
//                    nrRetries++;
                        aapsLogger.info(LTag.PUMPBTCOMM, "running command")
                        aapsLogger.info(LTag.PUMPBTCOMM, String(payload))
                        val count = 0
                        //                        while (lastReceived == lastReceivedCharacteristic && count < MAX_TRIES) {
                        if (bluetoothConnectionGatt == null || !bluetoothConnectionGatt!!.writeCharacteristic(
                                chara
                            )
                        ) {
                            aapsLogger.info(
                                LTag.PUMPBTCOMM,
                                String.format(
                                    "ERROR: writeCharacteristic failed for characteristic: %s",
                                    chara.uuid
                                )
                            )
                            needRetry = true
                            commandQueueBusy = CommandQueueBusy(false)
                            //                                break;
                        } else {
                            if (isConnected && commands[0].command == MedLinkCommandType.Connect) {
                                removeFirstCommand(true)
                            }
                            needRetry = false
                            aapsLogger.info(
                                LTag.PUMPBTCOMM,
                                String.format(
                                    "writing <%s> to characteristic <%s>",
                                    String(payload, StandardCharsets.UTF_8),
                                    chara.uuid
                                )
                            )
                            currentCommand.let {
                                it?.commandExecution = System.currentTimeMillis()
                            }
                        }
                        //                            count++;
//                            SystemClock.sleep(4000);
//                        }
                    } else {
                        aapsLogger.info(LTag.PUMPBTCOMM, "connection gatt is null need retry")
                        needRetry = true
                    }
                    aapsLogger.info(LTag.PUMPBTCOMM, "ZZZZZZZZZZZZZZZZZZZZZ")
                    if (needRetry && connectionStatus != ConnectionStatus.DISCOVERING) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "needretry")
                        if (currentCommand != null) {
                            clearExecutedCommand()
                        }
                        needRetry = false
                        close(true)
                    }
                    super.run()
                }
            }
        }
    }

    @Synchronized
    fun <B, C> addWriteCharacteristic(
        serviceUUID: UUID,
        charaUUID: UUID,
        msg: MedLinkPumpMessage<B, C>
    ) {
        aapsLogger.info(LTag.PUMPBTCOMM, "writeCharblocking")
        msg.firstCommand().code?.let { aapsLogger.info(LTag.PUMPBTCOMM, it) }
        aapsLogger.info(LTag.PUMPBTCOMM, "" + isConnected)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + bluetoothConnectionGatt)
        aapsLogger.info(LTag.PUMPBTCOMM, connectionStatus.name)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + msg.btSleepTime)
        aapsLogger.info(LTag.PUMPBTCOMM, "Command Added " + msg.commands.map { f -> f.command })
        if (msg.clearStops || msg.firstCommand()
                .isSameCommand(MedLinkCommandType.RemoveStopCommand)
        ) {
            removeStopCommands(false)
        }
        if ((//connectionStatus == ConnectionStatus.DISCONNECTING ||
                    connectionStatus != ConnectionStatus.CLOSED && connectionStatus != ConnectionStatus.CONNECTED) && System.currentTimeMillis() - connectionStatusChange > 60000
        ) {
            disconnect()
        }
        if (msg.btSleepTime > 0) {
            sleepSize = msg.btSleepTime
        }
        if (lastCommand == MedLinkCommandType.NoCommand || !msg.contains(lastCommand)) {
            aapsLogger.info(LTag.PUMPBTCOMM, "adding command ")
            addCommands(serviceUUID, charaUUID, msg)
        } else {
            aapsLogger.info(LTag.PUMPBTCOMM, "last command is the same " + lastCommand)
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "before connect")
        if (!connectionStatus.isConnecting || System.currentTimeMillis() - connectionStatusChange > 40000) {
            medLinkConnect()
        } else if (!connectionStatus.isConnecting && connectionStatus != ConnectionStatus.DISCONNECTING) {
            disconnect() ///TODO rethink
        }
    }

    fun <B, C> needToBeStarted(serviceUUID: UUID, charaUUID: UUID, command: MedLinkCommandType?) {
        if (commandNeedActivePump(command)) {
            if (currentCommand != null && currentCommand!!.secondCommand() == MedLinkCommandType.StopPump) {
                if (!currentCommand!!.hasFinished()) {
                    currentCommand!!.clearExecutedCommand()
                    currentCommand = null
                }
            }

        }

    }

    private fun removeStopCommands(priority: Boolean) {
        aapsLogger.info(LTag.PUMPBTCOMM, "removing stop")
        removeCommandFromQueue(MedLinkCommandType.StopPump, executionCommandQueue)
        if (!priority) {
            removeCommandFromQueue(MedLinkCommandType.StopPump, lowPriorityExecutionCommandQueue)
        }
    }

    private fun removeCommandFromQueue(
        commandType: MedLinkCommandType,
        queue: ConcurrentLinkedDeque<CommandExecutor<*>>,
    ) {
        val toRemove = queue.filter { it -> it?.hasFinished() == false && it.contains(commandType) }
        aapsLogger.info(LTag.PUMPBTCOMM, commandType.toString())
        queue.removeAll(
            toRemove
        )
    }

    private fun containsStart(): Boolean {
        val startMsg = MedLinkCommandType.StartPump
        return priorityExecutionCommandQueue.map { it.contains(startMsg) }.isNotEmpty()
    }

    private fun commandNeedActivePump(commandType: MedLinkCommandType?): Boolean {
        return commandType?.needActivePump == true
    }

    fun isBolus(commandType: MedLinkCommandType?): Boolean {
        return MedLinkCommandType.Bolus.isSameCommand(commandType) ||
                MedLinkCommandType.SMBBolus.isSameCommand(commandType) ||
                MedLinkCommandType.TBRBolus.isSameCommand(commandType)
    }

    private fun <B, C> addCommands(
        serviceUUID: UUID,
        charaUUID: UUID,
        msg: MedLinkPumpMessage<B, C>
    ) {
        synchronized(commandsToAdd) {
            // val command = CommandsToAdd(serviceUUID, charaUUID, msg)
            //            addCommand(command);
            synchronized(bolusExecutionQueue) {
                if (!mergeBolus(msg)) {
                    addWriteCharacteristic<B, C>(
                        serviceUUID,
                        charaUUID,
                        msg.commands,
                        msg.supplementaryCommands.isNotEmpty()
                    )
                    if (msg.supplementaryCommands.isNotEmpty()) {
                        addWriteCharacteristic<C, C>(
                            serviceUUID,
                            charaUUID,
                            msg.supplementaryCommands,
                            true
                        )
                    }
                    if (msg.firstCommand() == MedLinkCommandType.Bolus) absorbIntoManual()
                    if (isBolus(msg.firstCommand())) scheduleBolusWatchdog()
                    // 4i: a bolus waits behind one whose outcome is unclear: read M now, it decides join or run after
                    if (isBolus(msg.firstCommand()) && bolusTracker.current?.state.let { it == BolusState.UNRESOLVED || it == BolusState.UNKNOWN }) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS ${msg.firstCommand()} waits for ${bolusTracker.current}: reading M")
                        requestBolusStatus(System.currentTimeMillis())
                    }
                }
            }
            handleBolusCommand(msg, serviceUUID, charaUUID)
            handleCalibrateCommand(msg, serviceUUID, charaUUID)
            handleCalibrateFrequencyCommand(msg, serviceUUID, charaUUID)
            needToBeStarted<B, C>(serviceUUID, charaUUID, msg.commands[0].command)
        }
    }

    private fun <B, C> handleCalibrateFrequencyCommand(
        msg: MedLinkPumpMessage<B, C>,
        serviceUUID: UUID,
        charaUUID: UUID
    ) {
        // if (msg is CalibrateFrequencyMedLinkMessage) {
        //     val validation = msg.calibrateVerificationMessage
        //     addWriteCharacteristic<Stream<JSONObject>, Any>(serviceUUID, charaUUID, validation.commands, MedLinkBLE.CommandPriority.HIGH)
        // }
    }

    private fun <B, C> handleCalibrateCommand(
        msg: MedLinkPumpMessage<B, C>,
        serviceUUID: UUID,
        charaUUID: UUID
    ) {
        if (msg.contains(MedLinkCommandType.Calibrate)) {
            removeStopCommands(true)

        }
    }

    private fun handleBolusCommand(
        msg: MedLinkPumpMessage<*, *>,
        serviceUUID: UUID,
        charaUUID: UUID
    ) {
        if (commandNeedActivePump(msg.firstCommand())) {
            removeStopCommands(true)

        }
    }

    @SuppressLint("MissingPermission")
    fun connectGatt() {
        lastGattConnection = System.currentTimeMillis()
        reportBolusPhase(waitingBolusExecutor(), app.aaps.pump.common.hw.medlink.bolus.BolusPhase.CONNECTING)
        changeConnectionStatus(ConnectionStatus.CONNECTING)
        aapsLogger.info(LTag.PUMPBTCOMM, "Connecting gatt")
        if (medLinkDevice == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "RileyLink device is null, can't do connectGatt.")
            return
        }
        bluetoothConnectionGatt = medLinkDevice!!.connectGatt(
            context, false,
            bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE
        )
        if (bluetoothConnectionGatt == null) {
            aapsLogger.error(
                LTag.PUMPBTCOMM,
                "Failed to connect to Bluetooth Low Energy device at " + bluetoothAdapter!!.address
            )
        } else {
            gattConnected = true
            if (gattDebugEnabled) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Gatt Connected.")
            }
            val deviceName = bluetoothConnectionGatt!!.device.name
            if (StringUtils.isNotEmpty(deviceName)) {
                // Update stored name upon connecting (also for backwards compatibility for device where a name was not yet stored)
                preferences.put(MedLinkStringKey.Name, deviceName)
            } else {
                preferences.remove(MedLinkStringKey.Name)
            }
            if (this.medLinkServiceData != null) {
                this.medLinkServiceData?.rileylinkName = deviceName
                this.medLinkServiceData?.rileylinkAddress =
                    bluetoothConnectionGatt!!.device.address
            }
        }
    }

    //    private void updateActivity(Function resultActivity, String command) {
//        if (this.resultActivity != null) {
//            aapsLogger.info(LTag.PUMPBTCOMM, command + " resultActivity added " + this.resultActivity.toString());
//        }
//        this.resultActivity.add(new Resp(resultActivity, command));
//    }
//    public void addExecuteCommandToCommands() {
//        Function<Supplier<Stream<String>>, MedLinkStandardReturn<String>> ret = s -> {
//            return new MedLinkStandardReturn<String>(s, "");
//        };
//        MedLinkPumpMessage<String> msg = new MedLinkPumpMessage<String>(MedLinkCommandType.Connect,
//                MedLinkCommandType.NoCommand,
//                ret, medLinkServiceData, aapsLogger, sleepSize);
//        addCommands(UUID.fromString(GattAttributes.SERVICE_UUID),
//                UUID.fromString(GattAttributes.GATT_UUID),
//                msg, true);
//    }
    private fun addExecuteConnectCommand() {
        aapsLogger.info(LTag.PUMPBTCOMM, "ad ok conn command ")
        addWriteCharacteristic<Any, Any>(
            UUID.fromString(GattAttributes.SERVICE_UUID),
            UUID.fromString(GattAttributes.GATT_UUID),
            mutableListOf(
                CommandStructure(
                    MedLinkCommandType.Connect,
                    Optional.empty<Function<Supplier<Stream<String>>, MedLinkStandardReturn<Any>>>(),
                    Optional
                        .of(BleConnectCommand(aapsLogger, medLinkServiceData!!, null)),
                    commandPriority = CommandPriority.HIGH
                )
            ), false
        )
    }

    private var connectTries = 0
    private fun medLinkConnect() {
        if (connectTries > MAX_TRIES) {
            aapsLogger.info(LTag.PUMPBTCOMM, "max connection tries")
            return
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "connecting medlink")
        aapsLogger.info(LTag.PUMPBTCOMM, "" + (System.currentTimeMillis() - lastCloseAction))
        aapsLogger.info(LTag.PUMPBTCOMM, "" + (System.currentTimeMillis() - lastExecutedCommand))
        aapsLogger.info(LTag.PUMPBTCOMM, "" + gattConnected)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + bluetoothConnectionGatt)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + isConnected)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + isDiscovering)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + connectionStatus)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + sleepSize)
        if (connectionStatus == ConnectionStatus.CLOSED && !connectionStatus.isConnecting && System.currentTimeMillis() - lastCloseAction <= 5000 &&
            !gattConnected && bluetoothConnectionGatt != null
        ) {
            aapsLogger.info(LTag.PUMPBTCOMM, "closing")
            commandQueueBusy = CommandQueueBusy(false)
            close()
            return
        } else if (connectionStatus != ConnectionStatus.CLOSED && !connectionStatus.isConnecting && System.currentTimeMillis() - lastConfirmedCommand > 120000 && System.currentTimeMillis() - lastCloseAction > 500000 && bluetoothConnectionGatt != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "closing")
            commandQueueBusy = CommandQueueBusy(false)
            close()
            return
        } else if (connectionStatus == ConnectionStatus.EXECUTING || connectionStatus == ConnectionStatus.CONNECTED ||
            connectionStatus == ConnectionStatus.CONNECTING &&
            hasCommandsToExecute() && priorityExecutionCommandQueue.peekFirst() != null &&
            priorityExecutionCommandQueue.peekFirst()
                ?.nextCommand() == MedLinkCommandType.Notification
        ) {
            aapsLogger.info(LTag.PUMPBTCOMM, "nextcommand")
            nextCommand()
            return
        } else if (connectionStatus == ConnectionStatus.EXECUTING &&
            (lastExecutedCommand - System.currentTimeMillis() > 500000 ||
                    lastCloseAction - System.currentTimeMillis() > 500000)
        ) {
            aapsLogger.info(LTag.PUMPBTCOMM, "closing")
            commandQueueBusy = CommandQueueBusy(false)
            disconnect()
        } else if (System.currentTimeMillis() - connectionStatusChange > 180000) {
            aapsLogger.info(LTag.PUMPBTCOMM, "connection status changed")

            close(true)
            return
        } else if (connectionStatus.isConnecting) {
            return
        }
        synchronized(connectionStatus) {
            lastCharacteristic = ""
            if (connectionStatus == ConnectionStatus.CLOSED) {
                changeConnectionStatus(ConnectionStatus.CONNECTING)
                val sleep = System.currentTimeMillis() - lastCloseAction
                if (sleep < sleepSize) {
                    SystemClock.sleep(sleepSize - sleep)
                }
                aapsLogger.info(LTag.PUMPBTCOMM, "connecting gatt")
                connectGatt()
                //                if(currentCommand)
                if (bluetoothConnectionGatt == null) {
                    connectTries++
                    medLinkConnect()
                } else {
                    connectTries = 0
                }
            } else {
                pumpResponse = StringBuffer()
            }
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "ending medlinkconnect")
    }

    fun enableNotifications(): Boolean {
        val result = setNotificationBlocking(
            UUID.fromString(GattAttributes.SERVICE_UUID),  //
            UUID.fromString(GattAttributes.GATT_UUID), false
        )
        if (result.resultCode != BLECommOperationResult.RESULT_SUCCESS) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Error setting response count notification")
            return false
        }
        return true
    }

    private fun setNotificationBlocking(
        serviceUUID: UUID?,
        charaUUID: UUID?,
        disable: Boolean
    ): BLECommOperationResult {
        aapsLogger.debug("Enable medlink notification")
        aapsLogger.info(LTag.PUMPBTCOMM, "Enable medlink notification")
        aapsLogger.info(LTag.PUMPBTCOMM, "" + bluetoothConnectionGatt)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + mCurrentOperation)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + connectionStatus)
        val rval = BLECommOperationResult()
        if (bluetoothConnectionGatt == null) {
            medLinkConnect()
        }
        if (bluetoothConnectionGatt != null) {
            if (mCurrentOperation != null) {
                rval.resultCode = BLECommOperationResult.RESULT_BUSY
            } else {
                if (bluetoothConnectionGatt!!.getService(serviceUUID) == null) {
                    // Catch if the service is not supported by the BLE device
                    rval.resultCode = BLECommOperationResult.RESULT_NONE
                    aapsLogger.error(LTag.PUMPBTCOMM, "BT Device not supported")
                    //                    close();
                    disconnect()
                } else {
                    val characteristic = bluetoothConnectionGatt!!.getService(serviceUUID)
                        .getCharacteristic(charaUUID)
                    // Tell Android that we want the notifications
                    bluetoothConnectionGatt!!.setCharacteristicNotification(characteristic, true)
                    val list = characteristic!!.descriptors
                    //                    if (gattDebugEnabled) {
                    for (i in list.indices) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "Found descriptor: " + list[i].uuid)
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "Found descriptor: " + list[i].characteristic.uuid
                        )
                    }
                    //                    }
                    val descriptor = list[0]
                    // Tell the remote device to send the notifications

                    /////////////////////////////////////////////////////
                    // Check if characteristic is valid
                    if (characteristic == null) {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "ERROR: Characteristic is 'null', ignoring setNotify request"
                        )
                        rval.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
                        return rval
                    }

                    // Get the CCC Descriptor for the characteristic
//                    final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID));
                    if (descriptor == null) {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            String.format(
                                "ERROR: Could not get CCC descriptor for characteristic %s",
                                characteristic.uuid
                            )
                        )
                        rval.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
                        return rval
                    }

                    // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
                    val value: ByteArray
                    val properties = characteristic.properties
                    if (disable) {
                        value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    } else if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0) {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0) {
                        value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            String.format(
                                "ERROR: Characteristic %s does not have notify or indicate property",
                                characteristic.uuid
                            )
                        )
                        rval.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
                        return rval
                    }

//                    if (needToAddConnectCommand()) {
//                        addExecuteConnectCommand();
//                    }

                    // Queue Runnable to turn on/off the notification now that all checks have been passed
                    if (!notificationEnabled
                        && priorityExecutionCommandQueue.none { it.firstCommand() == MedLinkCommandType.Notification }
                        && lastReceivedCharacteristic < lastGattConnection
                    ) {
//                        if (executionCommandQueue.stream().noneMatch(f -> f.contains(MedLinkCommandType.Notification))) {
                        executionCommandQueue.stream().findFirst().map {
                            it.clearExecutedCommand()
                            false
                        }
                        priorityExecutionCommandQueue.addFirst(object : CommandExecutor<Any>(
                            MedLinkCommandType.Notification, aapsLogger
                        ) {
                            override fun run() {
                                isCommandConfirmed = false
                                // First set notification for Gatt object
                                synchronized(commandQueueBusy!!) {
                                    if (bluetoothConnectionGatt != null) {
                                        try {
                                            if (!bluetoothConnectionGatt!!.setCharacteristicNotification(
                                                    descriptor.characteristic,
                                                    true
                                                )
                                            ) {
                                                aapsLogger.info(
                                                    LTag.PUMPBTCOMM,
                                                    String.format(
                                                        "ERROR: setCharacteristicNotification failed for descriptor: %s",
                                                        descriptor.uuid
                                                    )
                                                )
                                            }
                                            nrRetries++
                                            // Then write to descriptor
                                            descriptor.value = value
                                            val result: Boolean
                                            result =
                                                bluetoothConnectionGatt!!.writeDescriptor(descriptor)
                                            notificationEnabled = true
                                            if (!result) {
                                                aapsLogger.info(
                                                    LTag.PUMPBTCOMM,
                                                    String.format(
                                                        "ERROR: writeDescriptor failed for descriptor: %s",
                                                        descriptor.uuid
                                                    )
                                                )
                                                needRetry = true
                                            } else {
                                                aapsLogger.info(
                                                    LTag.PUMPBTCOMM,
                                                    String.format(
                                                        " descriptor written: %s",
                                                        descriptor.uuid
                                                    )
                                                )
                                                needRetry = false
                                                commandQueueBusy = CommandQueueBusy(false)
                                                priorityExecutionCommandQueue.poll()
                                                //                                            SystemClock.sleep(6000);
//                                            if (needToAddConnectCommand()) {
//                                                addExecuteConnectCommand();
//                                            }
                                            }
                                        } catch (e: NullPointerException) {
                                            aapsLogger.info(LTag.PUMPBTCOMM, "npe  need retry")
                                            needRetry = true
                                        }
                                    } else {
                                        aapsLogger.info(
                                            LTag.PUMPBTCOMM,
                                            "connection gatt is null need retry"
                                        )
                                        needRetry = true
                                    }
                                }
                                super.run()
                            }
                        })
                        //                        }
                    }
                    //                    if (result) {
                    nextCommand()
                    rval.resultCode = BLECommOperationResult.RESULT_SUCCESS

//                    } else {
//                        aapsLogger.info(LTag.PUMPBTCOMM, "ERROR: Could not enqueue write command");
//                        rval.resultCode = RESULT_NOT_CONFIGURED;
//                    }

                    //////////////////////////////////////////////////////
//                    mCurrentOperation = new DescriptorWriteOperation(aapsLogger, bluetoothGatt, descr,
//                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
//                    mCurrentOperation.execute(this);
//                    if (mCurrentOperation.timedOut) {
//                        rval.resultCode = BLECommOperationResult.RESULT_TIMEOUT;
//                    } else if (mCurrentOperation.interrupted) {
//                        rval.resultCode = BLECommOperationResult.RESULT_INTERRUPTED;
//                    } else {
//                        rval.resultCode = BLECommOperationResult.RESULT_SUCCESS;
//                        notificationEnabled = true;
////                        nextCommand();
//
//                    }
//                }
                    aapsLogger.info(LTag.PUMPBTCOMM, "nulling currentoperation")
                    mCurrentOperation = null
                    return rval
                }
            }
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "setNotification_blocking: not configured!")
            rval.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
        }
        return rval
    }

    fun setConnected(connected: Boolean) {
        isConnected = connected
        gattConnected = connected
        if (!connected) {
            pumpResponse = StringBuffer()
        } else {
            lastConnection = System.currentTimeMillis()
            changeConnectionStatus(ConnectionStatus.CONNECTED)
            // T4: an unresolved bolus is checked with M before anything else
            if (bolusTracker.hasUnresolved()) requestBolusStatus(System.currentTimeMillis())
        }
    }

    fun setPumpModel(pumpModel: String?) {
        this.pumpModel = pumpModel
    }

    fun findMedLink(medLinkAddress: String) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "MedLink address: $medLinkAddress")
        // Must verify that this is a valid MAC, or crash.
        if (bleCommand == null) {
            bleCommand = BleCommand(aapsLogger, medLinkServiceData!!)
        }
        getBluetoothAdapter()
        medLinkDevice = bluetoothAdapter!!.getRemoteDevice(medLinkAddress)
        // if this succeeds, we get a connection state change callback?
        if (medLinkDevice != null) {
            connectGatt()
        } else {
            aapsLogger.error(
                LTag.PUMPBTCOMM,
                "RileyLink device not found with address: $medLinkAddress"
            )
        }
    }

    private var bluetoothConnectionGatt: BluetoothGatt? = null
    fun disconnect() {
        if (connectionStatus == ConnectionStatus.DISCONNECTING) {
            close(true)
        } else {
            changeConnectionStatus(ConnectionStatus.DISCONNECTING)
            servicesDiscovered = false
            pumpResponse = StringBuffer()
            aapsLogger.warn(LTag.PUMPBTCOMM, "Closing GATT connection")
            // Close old connection
            if (bluetoothConnectionGatt != null) {
                // Not sure if to disconnect or to close first..
                bluetoothConnectionGatt!!.disconnect()
                manualDisconnect = true
            } else {
                changeConnectionStatus(ConnectionStatus.CLOSED)
            }
            aapsLogger.info(LTag.PUMPBTCOMM, "Post disconnect")
            setConnected(false)
            isDiscovering = false
            commandQueueBusy = CommandQueueBusy(false)
        }
    }

    @JvmOverloads
    fun close(force: Boolean = false) {
        aapsLogger.info(LTag.PUMPBTCOMM, "closing")

        if (currentCommand != null && currentCommand!!.hasFinished() && currentCommand!!.nextFunction() != null) {
            currentCommand!!.nextBleCommand().map { it.applyResponse(this) }
            if (currentCommand!!.getCurrentCommand() != MedLinkCommandType.BolusStatus && currentCommand!!.getCurrentCommand() != MedLinkCommandType.StopPump && currentCommand!!.getCurrentCommand() != MedLinkCommandType.StartPump) {
                removeFirstCommand(true)
            }
        } else if (currentCommand != null &&
            currentCommand!!.commandList.any { it -> isBolus(it.command) }
        ) {
            holdCurrentBolus("connection closed")
        }
        if (force) {
            if (connectionStatus != ConnectionStatus.DISCONNECTING) {
                disconnect()
            }
            if (System.currentTimeMillis() - lastConfirmedCommand < 6000 && currentCommand != null && MedLinkCommandType.Connect.isSameCommand(
                    currentCommand!!.getCurrentCommand()
                )
            ) {
                aapsLogger.info(LTag.PUMPBTCOMM, "connecting gatt")
                connectGatt()
                lastCommand = MedLinkCommandType.NoCommand
                return
            }
        }
        //        this.tryingToClose++;
        aapsLogger.info(LTag.EVENTS, "" + commandQueueBusy)
        aapsLogger.info(LTag.EVENTS, "" + (lastConnection - System.currentTimeMillis()))
        if (commandQueueBusy != null && commandQueueBusy!!.busy) {
            aapsLogger.info(LTag.EVENTS, "trying to close to close")
            lastCommand = MedLinkCommandType.NoCommand
            return
        }
        changeConnectionStatus(ConnectionStatus.CLOSING)
        setConnected(false)
        servicesDiscovered = false
        isDiscovering = false
        notificationEnabled = false
        aapsLogger.info(LTag.EVENTS, "closing")
        if (bluetoothConnectionGatt != null) {
            bluetoothConnectionGatt!!.close()
            bluetoothConnectionGatt = null
        }
        changeConnectionStatus(ConnectionStatus.CLOSED)
        lastCloseAction = System.currentTimeMillis()
        previousLine = ""
        if (!hasCommandsToExecute() && commandsToAdd.isEmpty()) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Remaining commands")
            resultActivity.clear()
            addedCommands.clear()
            pumpResponse = StringBuffer()
            if (currentCommand != null) {
                if (currentCommand!!.hasFinished()) {
                    removeFirstCommand(false)
                } else {
                    currentCommand!!.clearExecutedCommand()
                }
            }
            currentCommand = null
            latestReceivedAnswer = 0L
            lastCharacteristic = ""
        } else {
            if (currentCommand != null) {
                if (currentCommand!!.hasFinished()) {
                    removeFirstCommand(false)
                } else {
                    currentCommand?.clearExecutedCommand()
                }
            }
            currentCommand = null
            medLinkConnect()
        }
        lastCommand = MedLinkCommandType.NoCommand
        aapsLogger.info(LTag.EVENTS, "ending close")
    }

    fun completedCommand() {
        completedCommand(false, waitNextIteration = false)
    }

    fun completedCommand(force: Boolean, waitNextIteration: Boolean) {
        lastCharacteristic = ""
        if (currentCommand != null && currentCommand!!.nrRetries > MAX_TRIES) {
            aapsLogger.info(LTag.PUMPBTCOMM, "maxtries")
            aapsLogger.info(LTag.PUMPBTCOMM, "removing command")
            val failed = currentCommand
            currentCommand?.commandFailed()
            lastCommand = MedLinkCommandType.NoCommand
            removeFirstCommand(true)
            disconnect()
            // T13: a START that gave up is retried later with alarm, never dropped silently
            if (failed?.secondCommand() == MedLinkCommandType.StartPump) scheduleStartRetry(failed, "max tries")
            return
        } else if (currentCommand != null && currentCommand!!.firstCommand() == MedLinkCommandType.CalibrateFrequencyArgument) {
            calibrateCommand = currentCommand
        } else if (calibrateCommand != null) {
            // addWriteCharacteristic(calibrateCommand.medLinkPumpMessage)
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "completed command")
        aapsLogger.info(
            LTag.PUMPBTCOMM,
            "priority queue size " + priorityExecutionCommandQueue.size
        )
        aapsLogger.info(LTag.PUMPBTCOMM, "normal queue size " + executionCommandQueue.size)
        aapsLogger.info(LTag.PUMPBTCOMM, "bolus queue size " + bolusExecutionQueue.size)
        aapsLogger.info(
            LTag.PUMPBTCOMM,
            "low priority queue size " + lowPriorityExecutionCommandQueue.size
        )
        commandQueueBusy = CommandQueueBusy(false)
        isRetrying = false
        if (currentCommand != null && currentCommand!!.commandExecution > 0L) {

            lastCommand =
                ((currentCommand?.secondCommand().takeIf { f -> f != MedLinkCommandType.NoCommand }
                    ?: currentCommand?.firstCommand())
                    ?: MedLinkCommandType.NoCommand)
            //Trying to fix first command removal when missconnect
            removeFirstCommand(force)
        }
        aapsLogger.info(
            LTag.PUMPBTCOMM,
            "priority queue size " + priorityExecutionCommandQueue.size
        )
        aapsLogger.info(LTag.PUMPBTCOMM, "normal queue size " + executionCommandQueue.size)
        aapsLogger.info(LTag.PUMPBTCOMM, "bolus queue size " + bolusExecutionQueue.size)
        aapsLogger.info(
            LTag.PUMPBTCOMM,
            "low priority queue size " + lowPriorityExecutionCommandQueue.size
        )


        currentCommand = null
        lastCharacteristic = ""
        val com = nextCommand
        if (hasCommandsToExecute() && MedLinkCommandType.Connect == com?.firstCommand() && isConnected) {
            aapsLogger.info(LTag.PUMPBTCOMM, "completed command")
            priorityExecutionCommandQueue.remove(com)
        }
        if (!waitNextIteration) {
            nextCommand()
        }
    }

    private val orderedQueues: List<ConcurrentLinkedDeque<CommandExecutor<*>>>
        get() = listOf(priorityExecutionCommandQueue, bolusExecutionQueue, executionCommandQueue, lowPriorityExecutionCommandQueue)

    private fun stopOrigin(executor: CommandExecutor<*>): StopOrigin? =
        if (executor.firstCommand() == MedLinkCommandType.StopStartPump && executor.secondCommand() == MedLinkCommandType.StopPump)
            executor.commandList.firstNotNullOfOrNull { it.commandHandler.orElse(null) as? BleStopCommand }?.origin ?: StopOrigin.AUTOMATIC
        else null

    private fun bleBolusCommand(executor: CommandExecutor<*>): BleBolusCommand? =
        executor.commandList.firstNotNullOfOrNull { it.commandHandler.orElse(null) as? BleBolusCommand }

    /** T14: the boluses this executor delivers, in queue order (one unless loop boluses were merged into it). */
    private fun bolusParts(executor: CommandExecutor<*>?): List<BleBolusCommand> {
        executor ?: return emptyList()
        synchronized(bolusMergeLock) {
            return mergedBolusParts[executor]?.toList() ?: listOfNotNull(bleBolusCommand(executor))
        }
    }

    /** Amount written to the pump: the sum of the parts when loop boluses were merged. */
    private fun bolusAmount(executor: CommandExecutor<*>): Double? =
        bolusParts(executor).takeIf { it.isNotEmpty() }?.let { parts -> LoopBolusMerge.total(parts.map { it.detailedBolusInfo.insulin }) }

    /**
     * T14: fold a TBR / SMB bolus into a queued loop bolus of the other type that was never posted,
     * so the pump gets one bolus with the sum.
     * 4i: any bolus type joins the tracked bolus while its dose is not written yet (QUEUED).
     * Returns true when merged (the message is not queued).
     */
    private fun mergeBolus(msg: MedLinkPumpMessage<*, *>): Boolean {
        val type = msg.firstCommand()
        if (!isBolus(type)) return false
        val newPart = msg.commands.firstNotNullOfOrNull { it.commandHandler.orElse(null) as? BleBolusCommand } ?: return false
        synchronized(bolusMergeLock) {
            val tracked = trackedBolusExecutor
            if (tracked != null && bolusTracker.current?.state == BolusState.QUEUED && joinTracked(tracked, listOf(newPart), "dose not written yet")) return true
            if (!LoopBolusMerge.isLoopBolus(type)) return false
            val target = bolusExecutionQueue.firstOrNull { e ->
                e !== trackedBolusExecutor && e !== currentCommand && !e.isInitialized &&
                    LoopBolusMerge.canMerge(mergedBolusParts[e]?.map { it.detailedBolusInfo.bolusType.toCommandType() } ?: listOf(e.firstCommand()), type)
            } ?: return false
            val own = bleBolusCommand(target) ?: return false
            val amountIndex = target.commandList.indexOfFirst { it.command == MedLinkCommandType.BolusAmount }
            if (amountIndex < 0) return false
            val parts = mergedBolusParts[target] ?: mutableListOf(own)
            val total = LoopBolusMerge.total(parts.map { it.detailedBolusInfo.insulin } + newPart.detailedBolusInfo.insulin)
            @Suppress("UNCHECKED_CAST")
            val list = target.commandList as MutableList<CommandStructure<Any?, BleCommand>>
            setBolusAmount(target, amountIndex, total)
            parts.add(newPart)
            mergedBolusParts[target] = parts
            aapsLogger.info(
                LTag.PUMPBTCOMM,
                "BOLUS merged ${type} ${newPart.detailedBolusInfo.insulin}U into queued ${target.firstCommand()}: " +
                    "${parts.joinToString { "${it.detailedBolusInfo.bolusType} ${it.detailedBolusInfo.insulin}U" }} -> ${total}U"
            )
            return true
        }
    }

    /** Rewrite the BOLUS amount of [target] (not written to the pump yet). */
    private fun setBolusAmount(target: CommandExecutor<*>, amountIndex: Int, total: Double) {
        @Suppress("UNCHECKED_CAST")
        val list = target.commandList as MutableList<CommandStructure<Any?, BleCommand>>
        val old = list[amountIndex]
        list[amountIndex] = CommandStructure(
            old.command, old.parseFunction, old.commandHandler,
            MedLinkCommandType.BolusAmount.getRaw(BolusMedLinkMessage.amountArgument(total)),
            old.commandPriority, old.errorFunction
        )
    }

    private fun BleBolusCommand.type(): MedLinkCommandType = detailedBolusInfo.bolusType.toCommandType()

    /** Bolus types an executor delivers (its own and the merged-in ones). */
    private fun partTypes(executor: CommandExecutor<*>): List<MedLinkCommandType> =
        bolusParts(executor).map { it.type() }.ifEmpty { listOf(executor.firstCommand()) }

    /**
     * 4i: [newParts] join the tracked bolus [target], which the pump has not received (not written yet,
     * or `M` proved it was not delivered and it is about to be re-sent). All or nothing.
     * Called with [bolusMergeLock] held.
     */
    private fun joinTracked(target: CommandExecutor<*>, newParts: List<BleBolusCommand>, reason: String): Boolean {
        if (newParts.isEmpty()) return false
        val own = bleBolusCommand(target) ?: return false
        val amountIndex = target.commandList.indexOfFirst { it.command == MedLinkCommandType.BolusAmount }
        if (amountIndex < 0) return false
        val parts = (mergedBolusParts[target] ?: mutableListOf(own)).toMutableList()
        for (p in newParts) {
            val consent = (parts + p).any { it.type() == MedLinkCommandType.Bolus && it.smbConsent }
            if (!LoopBolusMerge.canJoinTracked(parts.map { it.type() }, p.type(), consent)) return false
            parts.add(p)
        }
        val total = LoopBolusMerge.total(parts.map { it.detailedBolusInfo.insulin })
        if (!bolusTracker.onMerged(total, manual = newParts.any { it.type() == MedLinkCommandType.Bolus })) return false
        setBolusAmount(target, amountIndex, total)
        mergedBolusParts[target] = parts
        aapsLogger.info(
            LTag.PUMPBTCOMM,
            "BOLUS joined tracked ${target.firstCommand()} ($reason): ${parts.joinToString { "${it.detailedBolusInfo.bolusType} ${it.detailedBolusInfo.insulin}U" }} -> ${total}U"
        )
        return true
    }

    /**
     * 4i: `M` proved the tracked bolus was not delivered and it is re-sent: the boluses waiting behind it
     * (never posted) join it, so the pump gets one bolus. The ones that cannot join run after it.
     */
    private fun joinWaitingInto(target: CommandExecutor<*>) {
        synchronized(bolusMergeLock) {
            val queue = bolusExecutionQueue.toList()
            val removed = mutableListOf<CommandExecutor<*>>()
            queue.filter { isBolusExecutor(it) && it !== target && it !== currentCommand && !it.isInitialized }.forEach { e ->
                if (joinTracked(target, bolusParts(e), "M: not delivered")) {
                    mergedBolusParts.remove(e)
                    removed += withSupplementary(e, queue)
                }
            }
            if (removed.isEmpty()) return
            bolusExecutionQueue.removeIf { e -> removed.any { it === e } }
            removed.filter { isBolusExecutor(it) }.map { it.firstCommand() }.distinct().forEach { type ->
                if (bolusExecutionQueue.none { it.firstCommand() == type }) bolusExecutionHash.remove(type)
            }
        }
    }

    /**
     * 4i: bolus types pending: queued (with the merged-in parts) or sent and not resolved.
     * A new bolus of one of these types is refused; any other type is merged or waits for `M`.
     */
    fun pendingBolusTypes(): Set<MedLinkCommandType> =
        synchronized(bolusMergeLock) {
            val executors = bolusExecutionQueue.filter { isBolusExecutor(it) && it !== trackedBolusExecutor } +
                listOfNotNull(trackedBolusExecutor?.takeIf { !bolusTracker.canSendNewBolus() })
            executors.flatMap { partTypes(it) }.toSet()
        }

    /** A bolus executor and its supplementary `M` progress executor, which is queued right after it. */
    private fun withSupplementary(executor: CommandExecutor<*>, queue: List<CommandExecutor<*>>): List<CommandExecutor<*>> {
        val index = queue.indexOfFirst { it === executor }
        if (index < 0) return listOf(executor)
        val next = queue.getOrNull(index + 1)
        return if (next != null && !isBolusExecutor(next) && next.firstCommand() == MedLinkCommandType.BolusStatus) listOf(executor, next) else listOf(executor)
    }

    /**
     * T16: a manual bolus takes the loop boluses that were not posted yet into itself: the TBR always,
     * the SMB only with the user's consent. Anything that cannot be taken (an SMB without consent, a
     * second TBR) is failed: the loop asks again with the manual bolus in IOB. Called with the bolus
     * queue locked, right after the manual bolus was queued.
     */
    private fun absorbIntoManual() {
        synchronized(bolusMergeLock) {
            val queue = bolusExecutionQueue.toList()
            val manual = queue.lastOrNull { it.firstCommand() == MedLinkCommandType.Bolus && it !== trackedBolusExecutor } ?: return
            val own = bleBolusCommand(manual) ?: return
            val candidates = queue.filter {
                it !== manual && LoopBolusMerge.isLoopBolus(it.firstCommand()) && it !== trackedBolusExecutor && it !== currentCommand && !it.isInitialized
            }
            if (candidates.isEmpty()) return
            val amountIndex = manual.commandList.indexOfFirst { it.command == MedLinkCommandType.BolusAmount }
            if (amountIndex < 0) return
            val parts = mergedBolusParts[manual] ?: mutableListOf(own)
            val failed = mutableListOf<BleBolusCommand>()
            val removed = mutableListOf<CommandExecutor<*>>()
            candidates.forEach { e ->
                val eParts = mergedBolusParts.remove(e) ?: listOfNotNull(bleBolusCommand(e))
                eParts.forEach { p ->
                    if (LoopBolusMerge.canAbsorbIntoManual(parts.map { it.detailedBolusInfo.bolusType.toCommandType() }, p.detailedBolusInfo.bolusType.toCommandType(), own.smbConsent))
                        parts.add(p)
                    else failed.add(p)
                }
                removed += withSupplementary(e, queue)
            }
            val total = LoopBolusMerge.total(parts.map { it.detailedBolusInfo.insulin })
            setBolusAmount(manual, amountIndex, total)
            mergedBolusParts[manual] = parts
            bolusExecutionQueue.removeIf { e -> removed.any { it === e } }
            removed.filter { isBolusExecutor(it) }.map { it.firstCommand() }.distinct().forEach { type ->
                if (bolusExecutionQueue.none { it.firstCommand() == type }) bolusExecutionHash.remove(type)
            }
            aapsLogger.info(
                LTag.PUMPBTCOMM,
                "BOLUS manual merged: ${parts.joinToString { "${it.detailedBolusInfo.bolusType} ${it.detailedBolusInfo.insulin}U" }} -> ${total}U (smbConsent=${own.smbConsent})"
            )
            failed.forEach { part ->
                aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS ${part.detailedBolusInfo.bolusType} ${part.detailedBolusInfo.insulin}U not delivered: manual bolus queued")
                part.onQueueResult?.let { cb -> handler.post { cb(false, 0.0) } }
            }
        }
    }

    /** T16: SMB insulin queued (not posted) or in flight and unresolved, null when there is none. */
    fun pendingSmbAmount(): Double? {
        synchronized(bolusMergeLock) {
            val executors = bolusExecutionQueue.filter { isBolusExecutor(it) && it !== trackedBolusExecutor } +
                listOfNotNull(trackedBolusExecutor?.takeIf { !bolusTracker.canSendNewBolus() })
            val smb = executors.flatMap { bolusParts(it) }.filter { it.detailedBolusInfo.bolusType == app.aaps.core.data.model.BS.Type.SMB }
            return if (smb.isEmpty()) null else LoopBolusMerge.total(smb.map { it.detailedBolusInfo.insulin })
        }
    }

    /** T16: a manual bolus is queued or in flight and unresolved (a new SMB must wait for it in IOB). */
    fun hasPendingManualBolus(): Boolean = MedLinkCommandType.Bolus in pendingBolusTypes()

    /**
     * The tracked bolus used all its tries before the dose was written (connection kept dropping): drop it.
     * A manual one (or one with a manual part) alarms the user; a loop one just fails, the loop asks again.
     */
    private fun dropBolusOutOfTries(executor: CommandExecutor<*>) {
        val manual = MedLinkCommandType.Bolus in partTypes(executor)
        val group = withSupplementary(executor, bolusExecutionQueue.toList())
        orderedQueues.forEach { q -> q.removeIf { e -> group.any { it === e } } }
        if (bolusExecutionQueue.none { it.firstCommand() == executor.firstCommand() }) bolusExecutionHash.remove(executor.firstCommand())
        val amount = bolusAmount(executor)
        val attempts = bolusTracker.current?.attempts
        aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS ${executor.firstCommand()} ${amount}U dropped: not written after $attempts tries")
        onBolusExecutorDropped(executor, "not written after $attempts tries")
        bleBolusCommand(executor)?.onQueueResult?.let { cb -> handler.post { cb(false, 0.0) } }
        if (manual) raiseBolusAlarm("Manual bolus $amount U not delivered: it could not be sent to the pump after $attempts tries. Check the MedLink and bolus again if needed.")
    }

    private fun app.aaps.core.data.model.BS.Type.toCommandType(): MedLinkCommandType = when (this) {
        app.aaps.core.data.model.BS.Type.TBR -> MedLinkCommandType.TBRBolus
        app.aaps.core.data.model.BS.Type.SMB -> MedLinkCommandType.SMBBolus
        else                                 -> MedLinkCommandType.Bolus
    }

    /** T14: tell the merged-in boluses (not the executor's own) that nothing was delivered for them. */
    private fun failMergedParts(executor: CommandExecutor<*>?, reason: String) {
        executor ?: return
        val parts = synchronized(bolusMergeLock) { mergedBolusParts.remove(executor) } ?: return
        parts.drop(1).forEach { part ->
            aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS merged ${part.detailedBolusInfo.bolusType} ${part.detailedBolusInfo.insulin}U not delivered: $reason")
            part.onQueueResult?.let { cb -> handler.post { cb(false, 0.0) } }
        }
    }

    private fun isBolusExecutor(executor: CommandExecutor<*>) = isBolus(executor.firstCommand())

    private fun keepOnConnectionLoss(executor: CommandExecutor<*>) =
        isBolusExecutor(executor) || executor.firstCommand() == MedLinkCommandType.BolusStatus

    /**
     * The tracked bolus left the queues before its dose was written: fail the tracker request, otherwise it
     * stays QUEUED forever and every later SMB/TBR bolus is refused ("previous bolus unresolved").
     */
    private fun onBolusExecutorDropped(executor: CommandExecutor<*>, reason: String) {
        if (executor !== trackedBolusExecutor) return
        if (bolusTracker.onDropped(reason)) {
            aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS ${bolusAmount(executor)}U dropped before the dose was written ($reason): tracker released")
            failMergedParts(executor, reason)
            reportOwnResult(executor, false, 0.0)
        }
    }

    /**
     * T1 / T7 / T13: may this executor be sent now? Returns false when it must wait.
     * Side effects are idempotent: an `M` is queued at most once and delayed re-checks are coalesced.
     */
    private fun mayRun(executor: CommandExecutor<*>, now: Long): Boolean {
        if (stopOrigin(executor) == StopOrigin.AUTOMATIC && executor.getCurrentCommand() != MedLinkCommandType.StopPump) {
            if (now < stopHeldUntil) return false
            return when (val decision = StopGate.decide(StopOrigin.AUTOMATIC, bolusTracker, lastStatus, lastStatusSet, now)) {
                StopDecision.Send            -> {
                    stopGateAlarmed = false
                    true
                }
                StopDecision.ReadStatusFirst -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "automatic stop: reading M first")
                    requestBolusStatus(now)
                    false
                }

                is StopDecision.DeferUntil   -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "automatic stop deferred until ${decision.time}")
                    holdStopUntil(decision.time)
                    false
                }

                is StopDecision.Alarm        -> {
                    if (!stopGateAlarmed) {
                        stopGateAlarmed = true
                        raiseBolusAlarm(decision.reason)
                    }
                    holdStopUntil(now + BolusTracker.MINUTE)
                    false
                }
            }
        }
        if (executor === trackedBolusExecutor && executor.getCurrentCommand() == executor.firstCommand() &&
            bolusTracker.current?.state == BolusState.QUEUED && bolusTracker.triesLeft() == 0
        ) {
            // every post is a try, also when the connection drops before the dose is written
            dropBolusOutOfTries(executor)
            return false
        }
        if (isBolusExecutor(executor) && executor !== trackedBolusExecutor) {
            // 4i: boluses wait behind an unresolved one; that wait ends when its tries or its M failures run out
            if (!bolusTracker.canSendNewBolus()) {
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus waits: previous bolus ${bolusTracker.current} unresolved")
                return false
            }
            val allowedAt = bolusTracker.nextSendAllowedAt()
            if (now < allowedAt) {
                // the pump time has minute resolution: two equal boluses in one minute cannot be told apart (T5)
                scheduleNextCommand(allowedAt - now)
                return false
            }
            val latest = bolusTracker.latestSnapshot
            if (latest != null && latest.inProgress && now - latest.readAt < 10 * BolusTracker.MINUTE) {
                val programmed = latest.squareBolusAmount?.takeIf { it > 0.0 } ?: latest.lastBolusAmount
                val until = latest.readAt + ((programmed - (latest.deliveredAmount ?: 0.0)).coerceAtLeast(0.0) * BolusTracker.MS_PER_UNIT).toLong() +
                    BolusTracker.DELIVERY_MARGIN_MS
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus waits: pump is delivering another bolus")
                reportBolusPhase(executor, app.aaps.pump.common.hw.medlink.bolus.BolusPhase.WAITING_OTHER_BOLUS)
                if (now < until) scheduleNextCommand(until - now) else requestBolusStatus(now)
                return false
            }
            val baseline = bolusTracker.latestSnapshot
            if ((baseline == null || now - baseline.readAt > BASELINE_MAX_AGE_MS) && bolusStatusMessageProvider != null) {
                if (baselineRequestedFor !== executor) {
                    baselineRequestedFor = executor
                    baselineRequests = 0
                }
                if (baselineRequests < MAX_BASELINE_REQUESTS) {
                    if (!hasQueuedBolusStatus()) {
                        baselineRequests++
                        aapsLogger.info(LTag.PUMPBTCOMM, "bolus waits: reading baseline M first")
                        reportBolusPhase(executor, app.aaps.pump.common.hw.medlink.bolus.BolusPhase.READING_STATUS)
                        requestBolusStatus(now)
                    }
                    return false
                }
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus: no baseline M after $baselineRequests tries, sending without (re-send disabled)")
            }
        }
        return true
    }

    private fun holdStopUntil(time: Long) {
        stopHeldUntil = time
        scheduleNextCommand(time - System.currentTimeMillis())
    }

    private val nextCommand: CommandExecutor<*>?
        get() {
            if (!hasCommandsToExecute()) return null
            val now = System.currentTimeMillis()
            val nextComm = orderedQueues.asSequence().flatMap { it.asSequence() }.firstOrNull { mayRun(it, now) } ?: return null
            when (nextComm.postDecision(currentCommand)) {
                CommandExecutor.PostDecision.POST            -> Unit
                CommandExecutor.PostDecision.WAIT_FOR_ANSWER -> {
                    // all steps written and confirmed: posting again would write an empty payload ("invalid command")
                    aapsLogger.info(LTag.PUMPBTCOMM, "waiting for the answer of ${nextComm.firstCommand()}, not posting again")
                    return null
                }

                CommandExecutor.PostDecision.DROP_COMPLETED  -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "nothing left to write for ${nextComm.firstCommand()}, removing it: $nextComm")
                    orderedQueues.forEach { it.remove(nextComm) }
                    onBolusExecutorDropped(nextComm, "nothing left to write")
                    if (isBolusExecutor(nextComm) && bolusExecutionQueue.none { it.firstCommand() == nextComm.firstCommand() })
                        bolusExecutionHash.remove(nextComm.firstCommand())
                    // a written bolus stays under the tracker's control (M decides, never a blind re-send)
                    if (nextComm === trackedBolusExecutor && bolusTracker.current?.state.let { it != null && it != BolusState.QUEUED && !it.isFinal } &&
                        !onHoldCommandQueue.contains(nextComm)
                    ) onHoldCommandQueue.add(nextComm)
                    return nextCommand
                }
            }
            if (nextComm.firstCommand() == MedLinkCommandType.StopStartPump &&
                nextComm.getCurrentCommand() == MedLinkCommandType.StopStartPump &&
                StartStopSkipRule.shouldSkip(nextComm.secondCommand(), lastStatus, lastStatusSet, lastStartStopSent, now) ||
                isConnected && nextComm.firstCommand() == MedLinkCommandType.Connect
            ) {
                aapsLogger.info(LTag.PUMPBTCOMM, "skipping ${nextComm.secondCommand()}: pump already $lastStatus")
                currentCommand = nextComm
                removeFirstCommand(true)
                return nextCommand
            }
            return nextComm
        }

    //
//    public void retryCommand(MedLinkCommandType commandType) {
//        if (arguments.get(commandType) != null) {
//            MedLinkPumpMessage msg = arguments.remove(commandType);
//
//        }
//    }
    fun retryCommand() {
        lastCharacteristic = ""
        commandQueueBusy = CommandQueueBusy(false)
        if (currentCommand != null && currentCommand === trackedBolusExecutor &&
            bolusTracker.current?.state.let { it != null && it != BolusState.QUEUED && !it.isFinal }
        ) {
            // T7: a bolus whose dose was written is never retried; M decides
            holdCurrentBolus("retry")
            nextCommand()
            return
        }
        if (currentCommand != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Retrying " + currentCommand!!.nrRetries)
        }
        if (hasCommandsToExecute()) {
            if (currentCommand != null && currentCommand!!.nrRetries >= MAX_TRIES) {
//                currentCommand.clearExecutedCommand();
                // Max retries reached, give up on this one and proceed
                aapsLogger.error(LTag.PUMPBTCOMM, "Max number of tries reached")
                val failed = currentCommand
                removeFirstCommand(true)
                // T13: a START that gave up is retried later with alarm, never dropped silently
                if (failed?.secondCommand() == MedLinkCommandType.StartPump) scheduleStartRetry(failed, "max tries")
                completedCommand()
                currentCommand = null
                //            } else if (currentCommand == null) {

//            } else if (currentCommand.getCommand().isSameCommand(MedLinkCommandType.Bolus)) {
//                completedCommand();
            } else {
                nextCommand()
                isRetrying = true
            }
            //            disconnect();
        }
        //        if (!isConnected && !connectAdded && this.currentCommand != null &&
//                this.currentCommand.getCommand() != MedLinkCommandType.Connect.getRaw() &&
//                needToAddConnectCommand()
//        ) {
//            if (executionCommandQueue.peek().getRemainingBleCommand() != null &&
//                    executionCommandQueue.peek().getRemainingBleCommand().getCommand() != null) {
//                aapsLogger.info(LTag.PUMPBTCOMM,
//                        new String(executionCommandQueue.peek().getRemainingBleCommand().getCommand(), UTF_8));
//            }
//            aapsLogger.info(LTag.PUMPBTCOMM, "add connect command");
//            addExecuteCommandToCommands();
//        }
    }

    private fun needToAddConnectCommand(): Boolean {
        aapsLogger.info(LTag.PUMPBTCOMM, "" + commandsToAdd.size)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + executionCommandQueue.size)

        aapsLogger.info(LTag.PUMPBTCOMM, "" + priorityExecutionCommandQueue.size)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + (lastConnection == 0L && !hasCommandsToExecute()))
        aapsLogger.info(
            LTag.PUMPBTCOMM, "" + (!isConnected &&
                    hasCommandsToExecute() && nextCommand!!.nextCommand() != MedLinkCommandType.NoCommand && //
                    !MedLinkCommandType.Connect.isSameCommand(nextCommand!!.firstCommand()) &&
                    !MedLinkCommandType.ReadCharacteristic.isSameCommand(nextCommand!!.nextCommand()) &&  //
                    !MedLinkCommandType.Notification.isSameCommand(nextCommand!!.nextCommand()) &&
                    priorityExecutionCommandQueue
                        .none { it.firstCommand() == MedLinkCommandType.Connect } &&
                    currentCommand != null && currentCommand!!.firstCommand() != MedLinkCommandType.Connect))

        return (lastConnection == 0L && !hasCommandsToExecute()
                || (!isConnected &&
                hasCommandsToExecute() && nextCommand!!.nextCommand() != MedLinkCommandType.NoCommand && //
                !MedLinkCommandType.Connect.isSameCommand(nextCommand!!.firstCommand()) &&
                !MedLinkCommandType.ReadCharacteristic.isSameCommand(nextCommand!!.nextCommand()) &&  //
                !MedLinkCommandType.Notification.isSameCommand(nextCommand!!.nextCommand()) &&
                priorityExecutionCommandQueue
                    .none { it.firstCommand() == MedLinkCommandType.Connect } && 
                currentCommand != null && currentCommand!!.firstCommand() != MedLinkCommandType.Connect))
    }

    @Synchronized
    fun nextCommand() {
        aapsLogger.info(LTag.PUMPBTCOMM, "nextCommand $servicesDiscovered")
        aapsLogger.info(LTag.PUMPBTCOMM, "nextCommand ${commandQueueBusy.toString()}")
        aapsLogger.info(LTag.PUMPBTCOMM, "nextCommand $connectionStatus")
        if (currentCommand != null && currentCommand!!.nrRetries > MAX_TRIES) {
            removeFirstCommand(true)
        }
        printBuffer()
        if (connectionStatus == ConnectionStatus.EXECUTING || connectionStatus == ConnectionStatus.CONNECTED || connectionStatus == ConnectionStatus.DISCOVERING) {
            // If there is still a command being executed then bail out
            aapsLogger.info(LTag.PUMPBTCOMM, "CommandQueueBusy $commandQueueBusy")
            aapsLogger.info(LTag.PUMPBTCOMM, "bluetoothConnectionGatt $bluetoothConnectionGatt")
            if (currentCommand != null && currentCommand!!.hasFinished()) {
                removeFirstCommand(false)
            }
            if (commandQueueBusy != null && commandQueueBusy!!.busy) {
                return
            }
            // Check if we still have a valid gatt object
            try {
                if (bluetoothConnectionGatt == null) {
                    aapsLogger.error(
                        LTag.PUMPBTCOMM,
                        String.format(
                            "ERROR: GATT is 'null' for peripheral '%s', clearing command queue",
                            "Medlink"
                        )
                    )
                    // bolus commands and the M that verifies them survive a lost connection
                    priorityExecutionCommandQueue.removeIf { !keepOnConnectionLoss(it) }
                    executionCommandQueue.removeIf { !keepOnConnectionLoss(it) }
//                    bolusExecutionQueue.clear()
//                commandQueueBusy = false;
                    return
                }
                // processCommandToAdd()
                // Execute the next command in the queue
                if (nextCommand != null) {
                    aapsLogger.info(
                        LTag.PUMPBTCOMM,
                        "Queue size greater than 0 " + executionCommandQueue.size
                    )
                    currentCommand = nextCommand
                    //                    aapsLogger.info(LTag.PUMPBTCOMM, "" + currentCommand.hasFinished());
//                    if (currentCommand != null && currentCommand.nextCommand() != null &&
//                            connectionStatus == ConnectionStatus.EXECUTING &&
//                            currentCommand.nextCommand().isSameCommand(MedLinkCommandType.Connect)) {
//                        nextCommand();
//                        return;
//                    } else {
                    if (needToAddConnectCommand()) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "need to add")
                        addExecuteConnectCommand()
                        currentCommand = nextCommand
//                        nextCommand()
//                        return
                    }
                    commandQueueBusy = CommandQueueBusy(true)
                    if (currentCommand != null) {
                        aapsLogger.info(LTag.PUMPBTCOMM, currentCommand.toString())
                        beforePost(currentCommand!!)
                        bleHandler.post(currentCommand!!)
                    }
                } else if (isConnected) {
                    aapsLogger.info(LTag.PUMPBTCOMM, "empty execution queue")
                    // disconnect()
                }
            } catch (e: NullPointerException) {
                e.printStackTrace()
                return
            }
        }
    }

    private val allCommands: Stream<CommandExecutor<*>>
        get() = Stream.concat(
            Stream.concat(
                Stream.concat(
                    priorityExecutionCommandQueue.stream(),
                    executionCommandQueue.stream()
                ),
                bolusExecutionQueue.stream()
            ),
            lowPriorityExecutionCommandQueue.stream()
        )

    fun printBuffer() {
        val buf = StringBuilder("Print buffer")
        buf.append("\n")
        val it: MutableIterator<CommandExecutor<*>?> =
            allCommands.map { f: CommandExecutor<*> -> return@map f }.filter(
                { obj: CommandExecutor<*>? -> Objects.nonNull(obj) }).iterator()
        while (it.hasNext()) {
            buf.append(it.next().toString())
            buf.append("\n")
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "commands to add")
        val it1: Iterator<CommandsToAdd> = commandsToAdd.iterator()
        while (it1.hasNext()) {
            buf.append(it1.next().command.commands[0].command.code)
            buf.append("\n")
        }
        aapsLogger.info(LTag.PUMPBTCOMM, buf.toString())
    }

    fun discoverServices(): Boolean {
        aapsLogger.info(LTag.PUMPBTCOMM, connectionStatus.name)
        if (connectionStatus == ConnectionStatus.CLOSED) {
            if (bluetoothConnectionGatt == null) return false else if (bluetoothConnectionGatt!!.discoverServices() == true) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Starting to discover GATT Services.")
                return true
            } else {
                aapsLogger.error(LTag.PUMPBTCOMM, "Cannot discover GATT Services.")
            }
        }
        return false
    }

    fun clearNoResponse() {
        noResponse = 0
    }

    fun pumpConnectionError() {
        noResponse++
        if (noResponse > 2) {
            medLinkUtil?.sendBroadcastMessage(MedLinkConst.Intents.MedLinkConnectionError, context)
        }
    }

    companion object {

        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    init {
        val that = this
        handlerThread.start()
        characteristicThread.start()
        bleHandler = Handler(handlerThread.looper)
        handler = Handler(characteristicThread.looper)
        bluetoothGattCallback = object : BluetoothGattCallback() {
            private fun processCharacteristics(
                buffer: StringBuffer,
                answer: String
            ): Array<String> {
                return if (answer.contains("\n")) {
                    val index = answer.indexOf("\n")
                    buffer.append(answer.substring(0, index + 1))
                    processCharacteristics(buffer, answer.substring(index + 1))
                } else {
                    arrayOf(buffer.toString(), answer)
                }
            }

            fun isNotifying(characteristic: BluetoothGattCharacteristic): Boolean {
                return notifyingCharacteristics.contains(characteristic)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                val answer = String(characteristic.value).lowercase()
                if (lastCharacteristic == answer && answer.isNotEmpty()) {
                    aapsLogger.info(LTag.PUMPBTCOMM, "closing")
                    aapsLogger.info(LTag.PUMPBTCOMM, answer)

                    close(true)
                    return
                }

                lastReceivedCharacteristic = System.currentTimeMillis()
                removeNotificationCommand()
                // aapsLogger.info(LTag.PUMPBTCOMM, answer)
                // aapsLogger.info(LTag.PUMPBTCOMM, lastCharacteristic)
                if (lastCharacteristic == answer) {
                    setNotificationBlocking(
                        UUID.fromString(GattAttributes.SERVICE_UUID),  //
                        UUID.fromString(GattAttributes.GATT_UUID), true
                    )
                    if (currentCommand != null) {
                        currentCommand!!.clearExecutedCommand()
                    }
                    aapsLogger.info(LTag.PUMPBTCOMM, "next")

                    nextCommand()
                    return
                }
                // if (currentCommand != null) {
                //     aapsLogger.info(LTag.PUMPBTCOMM, currentCommand.toString())
                // }
                //                String[] processed = processCharacteristics(new StringBuffer(previousLine), answer);
//                previousLine = processed[1];
//                answer = processed[0];
//                aapsLogger.info(LTag.PUMPBTCOMM, answer)
                if (answer.trim { it <= ' ' }.isNotEmpty()) {
                    if (answer.contains("time to powerdown")) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "time to powerdown")

                        if (!answer.contains("5")) {
                            isConnected = true
                        }
                        commandQueueBusy = CommandQueueBusy(false)
                    }
                    if (currentCommand != null) {
                        val next = currentCommand!!.nextBleCommand()
                        if (next.isPresent) {
                            next.get().characteristicChanged(answer, that, lastCharacteristic)
                        } else {
                            // if (bleCommand != null)
                            logCa(answer, that)
                            // else nextCommand()
                        }
                        if (answer.contains("time to powerdown") && currentCommand?.let { !it.hasFinished() } == true) {
                            aapsLogger.info(LTag.PUMPBTCOMM, "clear executed")

//                            currentCommand.clearExecutedCommand();
                        }
                    } else {
                        aapsLogger.info(LTag.PUMPBTCOMM, "command null")
                        bleCommand!!.characteristicChanged(answer, that, lastCharacteristic)
                    }
                    latestReceivedAnswer = System.currentTimeMillis()
                    if (answer.contains("command con") && currentCommand != null) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "confirmed")
//                        characteristicChanged!!.characteristicChanged(answer, that, lastCharacteristic)

//                        if (!answer.startsWith(currentCommand.nextCommand().code.toLowerCase())) {
//                            aapsLogger.info(LTag.PUMPBTCOMM, "closing");
//                            close(true);
//                        } else {
                        aapsLogger.info(LTag.PUMPBTCOMM, "answer not empty")
                        isCommandConfirmed = true
                        lastConfirmedCommand = System.currentTimeMillis()
                        aapsLogger.info(LTag.PUMPBTCOMM, "command executed")
                        currentCommand!!.commandExecuted()

//                        }
                    }
//                    else if ((lastCharacteristic + answer).contains("pump status: suspend") || (lastCharacteristic + answer).contains("pump suspend state")) {
//                        lastPumpStatus = PumpRunningState.Suspended
//                    } else if ((lastCharacteristic + answer).contains("pump status: normal") || (lastCharacteristic + answer).contains("pump normal state")) {
//                        lastPumpStatus = PumpRunningState.Running
//                    } //TODO verify why this wasn't been used

                    //                if (answer.contains("bolus"))
//                    aapsLogger.info(LTag.PUMPBTCOMM, answer);
//                    aapsLogger.info(LTag.PUMPBTCOMM, "" + answer.contains("\n"));
                    lastCharacteristic = answer
                    radioResponseCountNotified.run()
                }
            }

            private fun logCa(answer: String, that: MedLinkBLE) {
                aapsLogger.info(LTag.PUMPBTCOMM, "logca")
                bleCommand?.characteristicChanged(answer, that, lastCharacteristic)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, status: Int,
            ) {
                super.onCharacteristicRead(gatt, characteristic, status)
                aapsLogger.info(LTag.PUMPBTCOMM, "onCharRead ")
                val statusMessage = getGattStatusMessage(status)
                if (gattDebugEnabled) {
                    aapsLogger.debug(
                        LTag.PUMPBTCOMM, ThreadUtil.sig() + "onCharacteristicRead ("
                                + GattAttributes.lookup(characteristic.uuid) + ") " + statusMessage + ":"
                                + ByteUtil.getHex(characteristic.value)
                    )
                }
                if (mCurrentOperation != null) {
                    mCurrentOperation!!.gattOperationCompletionCallback(
                        characteristic.uuid,
                        characteristic.value
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, status: Int,
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                aapsLogger.info(LTag.PUMPBTCOMM, "oncharwrite")
                val uuidString = GattAttributes.lookup(characteristic.uuid)
                if (gattDebugEnabled) {
                    aapsLogger.debug(
                        LTag.PUMPBTCOMM,
                        ThreadUtil.sig() + "onCharacteristicWrite " + getGattStatusMessage(status) + " "
                                + uuidString + " " + ByteUtil.shortHexString(characteristic.value)
                    )
                }
                if (mCurrentOperation != null) {
                    mCurrentOperation!!.gattOperationCompletionCallback(
                        characteristic.uuid,
                        characteristic.value
                    )
                }
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                aapsLogger.info(LTag.PUMPBTCOMM, "iscommandconfirmed $isCommandConfirmed")
                aapsLogger.info(LTag.PUMPBTCOMM, "iscommandconfirmed $currentCommand")
                if (currentCommand != null)
                    aapsLogger.info(
                        LTag.PUMPBTCOMM,
                        "iscommandconfirmed ${currentCommand!!.getCurrentCommand()}"
                    )
                if (isCommandConfirmed && currentCommand != null && currentCommand!!.getCurrentCommand().actionCommand) {
                    aapsLogger.info(LTag.PUMPBTCOMM, "Command cleared $newState")
                    currentCommand!!.clearExecutedCommand()
                }
                aapsLogger.error(LTag.PUMPBTCOMM, "Statechange $newState")
                // https://github.com/NordicSemiconductor/puck-central-android/blob/master/PuckCentral/app/src/main/java/no/nordicsemi/puckcentral/bluetooth/gatt/GattManager.java#L117
                if (status == 133) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Got the status 133 bug, closing gatt")
                    commandQueueBusy = CommandQueueBusy(false)
                    bug133 += 1
                    if (bug133 > 3) {
                        medLinkUtil!!.sendBroadcastMessage(
                            MedLinkConst.Intents.MedLinkConnectionError,
                            context
                        )
                    }
                    // T11: back off on the handler instead of sleeping 180 s on the GATT callback thread
                    val backoff = longArrayOf(5_000L, 15_000L, 60_000L, 180_000L)[minOf(bug133 - 1, 3)]
                    aapsLogger.info(LTag.PUMPBTCOMM, "Got the status 133 bug, closing gatt in $backoff ms")
                    handler.postDelayed({ close(true) }, backoff)
                    return
                }
                if (gattDebugEnabled) {
                    val stateMessage: String
                    stateMessage = if (newState == BluetoothProfile.STATE_CONNECTED) {
                        bug133 = 0
                        "CONNECTED"
                    } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                        "CONNECTING"
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        changeConnectionStatus(ConnectionStatus.CLOSED)
                        "DISCONNECTED"
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
//                        connectionStatus = ConnectionStatus.DISCONNECTING;
                        "DISCONNECTING"
                    } else {
                        "UNKNOWN newState ($newState)"
                    }
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "onConnectionStateChange " + getGattStatusMessage(status) + " " + stateMessage
                    )
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        medLinkUtil!!.sendBroadcastMessage(
                            MedLinkConst.Intents.BluetoothConnected,
                            context
                        )
                        val bondstate =
                            medLinkDevice!!.bondState // Take action depending on the bond state
                        if (bondstate == BluetoothDevice.BOND_NONE || bondstate == BluetoothDevice.BOND_BONDED) {
                            aapsLogger.info(LTag.PUMPBTCOMM, "Discoverying Services")
                            aapsLogger.info(LTag.PUMPBTCOMM, connectionStatus.name)
                            if (connectionStatus == ConnectionStatus.CONNECTING && bluetoothConnectionGatt != null) {
                                synchronized(bluetoothConnectionGatt!!) {
                                    changeConnectionStatus(ConnectionStatus.DISCOVERING)
                                    isDiscovering = true
                                    bluetoothConnectionGatt!!.discoverServices()
                                }
                            }
                        }
                    } else if (newState == BluetoothProfile.STATE_CONNECTING ||  //
                        newState == BluetoothProfile.STATE_DISCONNECTING
                    ) {
                        aapsLogger.debug(
                            LTag.PUMPBTCOMM,
                            "We are in {} state.",
                            if (status == BluetoothProfile.STATE_CONNECTING) "Connecting" else "Disconnecting"
                        )
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (latestReceivedAnswer > 0 && !hasCommandsToExecute()) {
                            medLinkUtil!!.sendBroadcastMessage(
                                MedLinkConst.Intents.CommandCompleted,
                                context
                            )
                            latestReceivedAnswer = 0L
                            //TODO fix handling this events
                        } else if (currentCommand != null && currentCommand!!.nrRetries > MAX_TRIES) {
                            medLinkUtil!!.sendBroadcastMessage(
                                MedLinkConst.Intents.MedLinkDisconnected,
                                context
                            )
                        }
                        commandQueueBusy = CommandQueueBusy(false)
                        aapsLogger.info(LTag.PUMPBTCOMM, "Disconnecting")
                        close(true)
                        aapsLogger.warn(LTag.PUMPBTCOMM, "MedLink Disconnected.")
                    } else {
                        aapsLogger.warn(
                            LTag.PUMPBTCOMM,
                            "Some other state: (status={},newState={})",
                            status,
                            newState
                        )
                    }
                } else {
                    commandQueueBusy = CommandQueueBusy(false)
                    aapsLogger.info(LTag.PUMPBTCOMM, "Connection status changed")
                    close(true)
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "BT State connected, GATT status {} ({})",
                        status,
                        getGattStatusMessage(status)
                    )
                }
            }

            private fun failureThatShouldTriggerBonding(gattStatus: Int): Boolean {
                if (gattStatus == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED || gattStatus == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || gattStatus == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) {
                    // Characteristic/descriptor is encrypted and needs bonding, bonding should be in progress already
                    // Operation must be retried after bonding is completed.
                    // This only seems to happen on Android 5/6/7.
                    // On newer versions Android will do retry internally
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        aapsLogger.info(
                            LTag.PUMPBTCOMM,
                            "operation will be retried after bonding, bonding should be in progress"
                        )
                        return true
                    }
                }
                return false
            }

            fun nonnullOf(source: ByteArray?): ByteArray {
                return source ?: ByteArray(0)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                aapsLogger.info(LTag.PUMPBTCOMM, "descriptor written " + descriptor.uuid)
                val parentCharacteristic = descriptor.characteristic
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.error(
                        LTag.PUMPBTCOMM, String.format(
                            "failed to write <%s> to descriptor of characteristic <%s> for device: '%s', status '%s' ",
                            currentCommand!!.nextCommand().code,
                            parentCharacteristic.uuid,
                            "Medlink",
                            status
                        )
                    )
                    if (failureThatShouldTriggerBonding(status)) return
                }

                // Check if this was the Client Characteristic Configuration Descriptor
                if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val value = nonnullOf(descriptor.value)
                        if (Arrays.equals(
                                value,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            ) ||
                            Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        ) {
                            notifyingCharacteristics.add(parentCharacteristic)
                        } else if (Arrays.equals(
                                value,
                                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                            )
                        ) {
                            notifyingCharacteristics.remove(parentCharacteristic)
                        }
                    }

                    aapsLogger.info(LTag.PUMPBTCOMM, "descriptor written " + descriptor.uuid)
                    //                    SystemClock.sleep(6000);
                    aapsLogger.info(LTag.PUMPBTCOMM, "descriptor written " + descriptor.uuid)
                    changeConnectionStatus(ConnectionStatus.DISCOVERING)
                    currentCommand?.commandExecuted()
                    completedCommand()
                }
            }

            override fun onDescriptorRead(
                gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                super.onDescriptorRead(gatt, descriptor, status)
                aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorRead ")
                mCurrentOperation!!.gattOperationCompletionCallback(
                    descriptor.uuid,
                    descriptor.value
                )
                if (gattDebugEnabled) {
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "onDescriptorRead " + getGattStatusMessage(status) + " status " + descriptor
                    )
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                aapsLogger.info(LTag.PUMPBTCOMM, "onMtuchanged ")
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onMtuChanged $mtu status $status")
                }
                completedCommand()
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)
                aapsLogger.info(LTag.PUMPBTCOMM, "onReadRemoteRssi ")
                if (gattDebugEnabled) {
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "onReadRemoteRssi " + getGattStatusMessage(status) + ": " + rssi
                    )
                }
                completedCommand()
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
                super.onReliableWriteCompleted(gatt, status)
                aapsLogger.info(LTag.PUMPBTCOMM, "onReliableWriteCompleted ")
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onReliableWriteCompleted status $status")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                aapsLogger.warn(LTag.PUMPBTCOMM, "onServicesDiscovered ")
                if (connectionStatus != ConnectionStatus.DISCOVERING) {
                    return
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val medLinkFound =
                        gatt.device.address == preferences.get(MedLinkStringPreferenceKey.MacAddress)
                    if (gattDebugEnabled) {
                        aapsLogger.warn(
                            LTag.PUMPBTCOMM,
                            "onServicesDiscovered " + getGattStatusMessage(status)
                        )
                    }
                    aapsLogger.info(
                        LTag.PUMPBTCOMM, "Gatt device is MedLink device: " +
                                medLinkFound + " " + gatt.device.name + " " +
                                gatt.device.address
                    )
                    if (medLinkFound) {
                        mIsConnected = true
                        val message = Intent()
                        message.action = MedLinkConst.Intents.MedLinkReady
                        message.putExtra("BatteryLevel", batteryLevel)
                        message.putExtra("FirmwareVersion", firmwareVersion)
                        medLinkUtil!!.sendBroadcastMessage(message, context)
                        servicesDiscovered = true
                        commandQueueBusy = CommandQueueBusy(false)
                        enableNotifications()
                        //                        nextCommand();
                    } else {
                        mIsConnected = false
                        if (System.currentTimeMillis() - latestReceivedAnswer > 600000) {
                            medLinkServiceData!!.setServiceState(
                                MedLinkServiceState.MedLinkError,
                                MedLinkError.DeviceIsNotMedLink
                            )
                        } else {
                            aapsLogger.info(
                                LTag.PUMPBTCOMM,
                                "onServicesDiscovered " + getGattStatusMessage(status)
                            )
                            disconnect()
                        }
                    }
                } else {
                    aapsLogger.info(
                        LTag.PUMPBTCOMM,
                        "onServicesDiscovered " + getGattStatusMessage(status)
                    )
                    disconnect()
                    medLinkUtil!!.sendBroadcastMessage(
                        RileyLinkConst.Intents.RileyLinkGattFailed,
                        context
                    )
                }
            }
        }
    }

    fun registerRadioResponseCountNotification(notifier: Runnable) {
        radioResponseCountNotified = notifier
    }

    fun removeBolusAndSuppCommand() {
        if (currentCommand != null) {
            synchronized(currentCommand!!) {
                removeFirstCommand(true)
                nextCommand()
                removeFirstCommand(true)
            }
        }
    }

    // ---- bolus / stop safety (BUGs_TASKS T0-T13) --------------------------------------------

    /** T1: `A` answered "bolusing" for an automatic stop: keep the stop queued and decide again after `M`. */
    fun holdStop() {
        currentCommand?.clearExecutedCommand()
        currentCommand = null
        commandQueueBusy = CommandQueueBusy(false)
        lastCharacteristic = ""
        nextCommand()
    }

    /** The bolus answer is unclear (connection, pump busy, suspended after write): verify with `M`, never re-send blindly. */
    fun onBolusUnclear() {
        val written = currentCommand === trackedBolusExecutor && bolusTracker.current?.state.let { it != null && it != BolusState.QUEUED }
        if (written) {
            holdCurrentBolus("unclear answer")
        } else {
            // not written yet (pump busy): read M; the picker keeps the bolus waiting while M shows a bolus in progress
            currentCommand?.clearExecutedCommand()
            currentCommand = null
            requestBolusStatus(System.currentTimeMillis())
        }
        commandQueueBusy = CommandQueueBusy(false)
        lastCharacteristic = ""
        nextCommand()
    }

    /** @return true when the dose was never written, so the plugin may deliver it again. */
    fun onBolusRejectedBeforeWrite(): Boolean {
        val tracked = currentCommand != null && currentCommand === trackedBolusExecutor
        val notWritten = if (tracked) bolusTracker.onRejectedBeforeWrite() else true
        if (notWritten) failMergedParts(currentCommand, "rejected before write")
        return notWritten
    }

    /**
     * The whole answer of the BOLUS command (joined BLE chunks, up to "ready"). It tells whether the bolus is
     * delivering, delivered or not delivered, so `M` is only needed to follow a delivering bolus.
     */
    fun onBolusAnswer(text: String) {
        val executor = currentCommand
        if (executor == null || executor !== trackedBolusExecutor) return
        executeBolusAction(bolusTracker.onBolusAnswer(app.aaps.pump.common.hw.medlink.bolus.BolusCommandAnswer.parse(text, System.currentTimeMillis())))
    }

    /** Delivering: tell the bolus owner (progress dialog of a manual bolus). Estimated from the time when `M` has no amount. */
    private fun reportBolusProgress(r: app.aaps.pump.common.hw.medlink.bolus.BolusRequest, delivered: Double) {
        val own = trackedBolusExecutor?.let { bleBolusCommand(it) } ?: return
        val callback = own.onProgress ?: return
        val estimated = ((System.currentTimeMillis() - r.sentAt).toDouble() / BolusTracker.MS_PER_UNIT).coerceIn(0.0, r.requested * 0.95)
        val shown = if (delivered > 0.0) delivered else estimated
        handler.post { callback(shown, r.requested) }
    }

    /** Last phase told to a bolus owner: the same phase is not repeated (the picker runs many times). */
    private var lastBolusPhase: Triple<CommandExecutor<*>, app.aaps.pump.common.hw.medlink.bolus.BolusPhase, Int>? = null

    /** The bolus that waits to be delivered: the tracked one before it delivers, else the first queued bolus. */
    private fun waitingBolusExecutor(): CommandExecutor<*>? {
        val tracked = trackedBolusExecutor
        val state = bolusTracker.current?.state
        if (tracked != null && state != null && !state.isFinal) return tracked.takeIf { state != BolusState.DELIVERING }
        return bolusExecutionQueue.firstOrNull { isBolusExecutor(it) }
    }

    /** 4p: tell the bolus owner (progress dialog of a manual bolus) what it waits for. Nothing once the pump delivers it. */
    private fun reportBolusPhase(executor: CommandExecutor<*>?, phase: app.aaps.pump.common.hw.medlink.bolus.BolusPhase) {
        executor ?: return
        val callback = bleBolusCommand(executor)?.onPhase ?: return
        val r = bolusTracker.current
        val tracked = executor === trackedBolusExecutor && r != null && !r.state.isFinal
        if (tracked && r!!.state == BolusState.DELIVERING) return
        val attempt = if (tracked && phase == app.aaps.pump.common.hw.medlink.bolus.BolusPhase.SENDING) r!!.attempts else 0
        val key = Triple(executor, phase, attempt)
        if (lastBolusPhase == key) return
        lastBolusPhase = key
        handler.post { callback(phase, attempt, BolusTracker.MAX_ATTEMPTS) }
    }

    /** The result of the executor's own bolus (part 0); the owner ignores repeated calls. */
    private fun reportOwnResult(executor: CommandExecutor<*>?, success: Boolean, delivered: Double) {
        val cb = executor?.let { bleBolusCommand(it) }?.onQueueResult ?: return
        handler.post { cb(success, delivered) }
    }

    /** Every `M` answer (null when it cannot be parsed). */
    fun onBolusStatus(snapshot: BolusStatusSnapshot?) {
        if (snapshot != null) {
            mInvalidInARow = 0
            alarmOtherBolusInProgress(snapshot)
        }
        executeBolusAction(bolusTracker.onStatus(snapshot))
    }

    /** Every `A` answer. */
    fun onPumpAnswerState(state: PumpAnswerState) {
        executeBolusAction(bolusTracker.onPumpState(state))
    }

    private fun holdCurrentBolus(reason: String) {
        val executor = currentCommand ?: return
        val state = bolusTracker.current?.state
        if (executor === trackedBolusExecutor && state != null && state != BolusState.QUEUED) {
            aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS hold ($reason) state=$state: verifying with M")
            if (!onHoldCommandQueue.contains(executor)) onHoldCommandQueue.add(executor)
            removeFirstCommand(true)
            needToCheckOnHold = true
            executeBolusAction(bolusTracker.onDisconnect())
        } else {
            // the dose was not written yet: the command stays queued and runs normally
            aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS ($reason) before the dose was written, keeping it queued")
            executor.clearExecutedCommand()
        }
    }

    private fun executeBolusAction(action: BolusAction) {
        try {
            executeBolusActionNow(action)
        } finally {
            updatePumpBlock()
            scheduleBolusWatchdog()
        }
    }

    /**
     * 4j case 2: the pump delivers a bolus the driver did not send (pump buttons, square bolus) while a bolus
     * waits: tell the user how much insulin the pump is delivering. Once per pump bolus.
     */
    private fun alarmOtherBolusInProgress(snapshot: BolusStatusSnapshot) {
        val waiting = bolusExecutionQueue.any { isBolusExecutor(it) } || !bolusTracker.canSendNewBolus()
        if (!waiting || !bolusTracker.isOtherBolusInProgress(snapshot) || lastExternalBolusAlarm == snapshot.lastBolusTime) return
        lastExternalBolusAlarm = snapshot.lastBolusTime
        val programmed = snapshot.squareBolusAmount?.takeIf { it > 0.0 } ?: snapshot.lastBolusAmount
        raiseBolusAlarm(
            String.format(
                java.util.Locale.US,
                "The pump is delivering a bolus of %.2f U (%.2f U delivered so far)%s that was not sent by AAPS. The queued bolus waits until it ends.",
                programmed, snapshot.deliveredAmount ?: 0.0, if ((snapshot.squareBolusAmount ?: 0.0) > 0.0) ", square/dual wave" else ""
            )
        )
    }

    /** Recompute the lock; tell the plugin when it changes. */
    private fun updatePumpBlock() {
        val block = PumpLockRule.decide(confirmedWithoutEffect)
        if (block == pumpBlock) return
        aapsLogger.warn(LTag.PUMPBTCOMM, "pump block ${pumpBlock ?: "none"} -> ${block ?: "none"} (confirmed without effect: $confirmedWithoutEffect)")
        pumpBlock = block
        pumpBlockHandler?.let { h -> handler.post { h(block) } }
    }

    /** START/STOP/bolus confirmed by the MedLink and the pump did not change: lock evidence. */
    private fun onCommandWithoutEffect(what: String) {
        confirmedWithoutEffect++
        aapsLogger.warn(LTag.PUMPBTCOMM, "command without effect ($what), $confirmedWithoutEffect in a row")
        updatePumpBlock()
    }

    /** The pump changed state or delivered a bolus: it accepts commands. */
    private fun onCommandEffect(what: String) {
        if (confirmedWithoutEffect == 0) return
        aapsLogger.info(LTag.PUMPBTCOMM, "pump accepts commands again ($what)")
        confirmedWithoutEffect = 0
        updatePumpBlock()
    }

    private fun scheduleBolusWatchdog() {
        handler.removeCallbacks(bolusWatchdog)
        handler.postDelayed(bolusWatchdog, WATCHDOG_MS)
    }

    /**
     * Runs whether or not the MedLink is connected. While a written bolus is unresolved, `M` is read at least
     * every [BLOCK_STATUS_RETRY_MS] (reconnecting if needed); an `M` asked that long ago and never answered
     * (connection dropped, no MedLink) is an `M` failure for the bolus.
     */
    private fun runBolusWatchdog() {
        val now = System.currentTimeMillis()
        val r = bolusTracker.current
        val stuck = r != null && bolusTracker.hasUnresolved() && (r.state != BolusState.DELIVERING || now > r.expectedEndAt)
        if (r != null && stuck && now - maxOf(lastBlockStatusRequest, r.lastSnapshot?.readAt ?: 0L, r.sentAt) >= BLOCK_STATUS_RETRY_MS) {
            val unanswered = lastBlockStatusRequest > r.sentAt && (r.lastSnapshot?.readAt ?: 0L) < lastBlockStatusRequest
            lastBlockStatusRequest = now
            if (unanswered) executeBolusAction(bolusTracker.onStatusFailed("no M answer in ${BLOCK_STATUS_RETRY_MS / 1000} s"))
            if (bolusTracker.hasUnresolved()) {
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus unresolved: reading M again for ${bolusTracker.current}")
                requestBolusStatus(now)
            }
        }
        if (!bolusTracker.canSendNewBolus() || bolusExecutionQueue.any { isBolusExecutor(it) }) scheduleBolusWatchdog()
    }

    /**
     * `M` answered "invalid command" (lost communication with the pump, or a MedLink problem). It counts as an
     * `M` failure for the bolus in flight and `M` is read again; [M_INVALID_RESET] in a row reset the connection.
     */
    fun onBolusStatusInvalid() {
        mInvalidInARow++
        val executor = currentCommand
        aapsLogger.warn(LTag.PUMPBTCOMM, "M answered invalid command ($mInvalidInARow in a row)")
        lastCharacteristic = ""
        commandQueueBusy = CommandQueueBusy(false)
        if (executor != null && executor.firstCommand() == MedLinkCommandType.BolusStatus) {
            orderedQueues.forEach { q -> q.removeIf { it === executor } }
            currentCommand = null
        }
        // queues the next M (bolus in flight); a baseline M is asked again by the bolus picker
        executeBolusAction(bolusTracker.onStatusFailed("M answered invalid command"))
        if (mInvalidInARow >= M_INVALID_RESET) {
            mInvalidInARow = 0
            aapsLogger.warn(LTag.PUMPBTCOMM, "M invalid $M_INVALID_RESET times in a row: resetting the MedLink connection")
            // close() reconnects when commands are left
            close(true)
        } else handler.post { nextCommand() }
    }

    private fun executeBolusActionNow(action: BolusAction) {
        when (action) {
            BolusAction.None          -> Unit
            is BolusAction.ReadStatusAt -> requestBolusStatus(action.time)
            BolusAction.Resend        -> resendHeldBolus()
            is BolusAction.Record     -> {
                aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS record ${action.amount}U at pump time ${action.pumpTime}")
                recordParts(trackedBolusExecutor, action.amount, action.pumpTime)
                dropHeldBolus()
            }

            is BolusAction.Fail       -> {
                aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS failed: ${action.reason}")
                failMergedParts(trackedBolusExecutor, action.reason)
                reportOwnResult(trackedBolusExecutor, false, bolusTracker.current?.appliedTotal ?: 0.0)
                dropHeldBolus()
            }

            is BolusAction.Alarm      -> {
                raiseBolusAlarm(action.reason)
                if (bolusTracker.current?.state?.isFinal == true) {
                    failMergedParts(trackedBolusExecutor, action.reason)
                    reportOwnResult(trackedBolusExecutor, false, bolusTracker.current?.appliedTotal ?: 0.0)
                    dropHeldBolus()
                }
            }

            is BolusAction.AskUser    -> {
                // T7: a manual bolus is never re-sent automatically; tell the user what M showed
                val r = bolusTracker.current
                raiseBolusAlarm(
                    "Bolus ${r?.requested} U was not found in the pump history (last bolus ${action.snapshot?.lastBolusAmount} U). " +
                        "Check the pump and bolus again manually if needed."
                )
                executeBolusAction(bolusTracker.onUserResendAnswer(false))
            }
        }
    }

    /**
     * T14: record the delivered amount; a merged bolus is split into its parts (one TBR, one SMB),
     * each recorded with its own bolus info at its own time (see [LoopBolusMerge]).
     */
    private fun recordParts(executor: CommandExecutor<*>?, amount: Double, pumpTime: Long) {
        val parts = bolusParts(executor)
        synchronized(bolusMergeLock) { executor?.let { mergedBolusParts.remove(it) } }
        val recorder = bolusRecorder
        if (parts.isEmpty()) {
            if (recorder == null) aapsLogger.error(LTag.PUMPBTCOMM, "BOLUS no recorder installed, record lost")
            else handler.post { recorder(amount, pumpTime, null) }
            reportOwnResult(executor, amount > 0.0, amount)
            return
        }
        val amounts = LoopBolusMerge.split(parts.map { it.detailedBolusInfo.insulin }, amount)
        parts.forEachIndexed { i, part ->
            val partAmount = amounts[i]
            val partTime = LoopBolusMerge.partTime(pumpTime, i)
            if (parts.size > 1) aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS part ${part.detailedBolusInfo.bolusType} ${partAmount}U of ${part.detailedBolusInfo.insulin}U at $partTime")
            if (partAmount > 0.0) {
                if (recorder == null) aapsLogger.error(LTag.PUMPBTCOMM, "BOLUS no recorder installed, record lost")
                else handler.post { recorder(partAmount, partTime, part.detailedBolusInfo) }
            }
            // every part, the executor's own one too, gets its result here: the bolus answer callback does not decide
            part.onQueueResult?.let { cb -> handler.post { cb(partAmount > 0.0, partAmount) } }
        }
    }

    private fun raiseBolusAlarm(reason: String) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "BOLUS alarm: $reason")
        val alarm = bolusAlarmHandler ?: return
        handler.post { alarm(reason) }
    }

    private fun dropHeldBolus() {
        trackedBolusExecutor?.let { onHoldCommandQueue.remove(it) }
        needToCheckOnHold = onHoldCommandQueue.isNotEmpty()
    }

    private fun resendHeldBolus() {
        val executor = trackedBolusExecutor
        if (executor == null || !onHoldCommandQueue.remove(executor)) {
            // nothing to re-send: fail the request instead of guessing
            aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS resend requested but no held command, failing")
            bolusTracker.onRejectedBeforeWrite()
            return
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS resend after M proved it was not started")
        needToCheckOnHold = onHoldCommandQueue.isNotEmpty()
        joinWaitingInto(executor)
        executor.clearExecutedCommand()
        bolusExecutionQueue.addFirst(executor)
        nextCommand()
    }

    /** An `M` waiting in a queue. The one running now does not count: its answer may ask for a fresh read. */
    private fun hasQueuedBolusStatus(): Boolean =
        orderedQueues.any { q -> q.any { it !== currentCommand && it.firstCommand() == MedLinkCommandType.BolusStatus } }

    /** Queue an `M` at the head of the priority queue at [time] (coalesced, no sleeping). */
    private fun requestBolusStatus(time: Long) {
        val now = System.currentTimeMillis()
        if (time > now) {
            if (time >= pendingStatusAt && pendingStatusAt > now) return
            pendingStatusAt = time
            handler.postDelayed({
                                    if (pendingStatusAt <= System.currentTimeMillis()) pendingStatusAt = Long.MAX_VALUE
                                    requestBolusStatus(System.currentTimeMillis())
                                }, time - now)
            return
        }
        if (hasQueuedBolusStatus()) return
        val message = bolusStatusMessageProvider?.invoke()
        if (message == null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS cannot read M: no status message provider")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val commands = message.commands as MutableList<CommandStructure<Any?, BleCommand>>
        val executor = synchronized(commandQueueBusy ?: this) {
            buildCommandExecutor(UUID.fromString(GattAttributes.GATT_UUID), UUID.fromString(GattAttributes.SERVICE_UUID), commands)
        }
        priorityExecutionCommandQueue.addFirst(executor)
        aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS M queued")
        // may be called from inside the command picker: never re-enter nextCommand() synchronously
        handler.post { if (connectionStatus == ConnectionStatus.CLOSED) medLinkConnect() else nextCommand() }
    }

    private val scheduledNextCommand = Runnable {
        synchronized(this) { scheduledNextCommandAt = 0L }
        nextCommand()
    }
    private var scheduledNextCommandAt = 0L

    /**
     * One pending wake-up at most. [mayRun] runs many times per [nextCommand] (the picker is evaluated several times
     * by [needToAddConnectCommand]); posting a new wake-up on every evaluation multiplied them every second while a
     * bolus or STOP waited, flooding the BLE thread and the heap.
     */
    private fun scheduleNextCommand(delay: Long) {
        val at = System.currentTimeMillis() + maxOf(delay, 1_000L)
        synchronized(this) {
            if (scheduledNextCommandAt in 1..at) return
            scheduledNextCommandAt = at
        }
        handler.removeCallbacks(scheduledNextCommand)
        handler.postDelayed(scheduledNextCommand, maxOf(delay, 1_000L))
    }

    /** Bookkeeping right before an executor is written. */
    private fun beforePost(executor: CommandExecutor<*>) {
        val now = System.currentTimeMillis()
        if (executor.firstCommand() == MedLinkCommandType.StopStartPump) lastStartStopSent = now
        if (!isBolusExecutor(executor)) return
        if (executor !== trackedBolusExecutor) {
            // T14: untracked boluses may be merged by mergeBolus(); once tracked, only joinTracked() (4i)
            synchronized(bolusMergeLock) {
                val origin = if (executor.firstCommand() == MedLinkCommandType.Bolus) BolusOrigin.MANUAL else BolusOrigin.LOOP
                val amount = bolusAmount(executor) ?: 0.0
                if (bolusTracker.onQueued("${executor.firstCommand()}-${System.identityHashCode(executor)}", origin, amount) == null) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "BOLUS posted while another bolus is unresolved")
                    return
                }
                trackedBolusExecutor = executor
            }
            bolusTracker.onBaseline(bolusTracker.latestSnapshot?.takeIf { now - it.readAt <= BASELINE_MAX_AGE_MS })
        }
        // every post of the first step is a try, whether or not the dose gets written
        if (executor === trackedBolusExecutor && executor.getCurrentCommand() == executor.firstCommand()) {
            bolusTracker.onTry()
            reportBolusPhase(executor, app.aaps.pump.common.hw.medlink.bolus.BolusPhase.SENDING)
        }
        // the dose is committed when the amount is written
        if (executor.getCurrentCommand() == MedLinkCommandType.BolusAmount) {
            // 4i: under the merge lock, so nothing joins the bolus once its amount is committed
            val action = synchronized(bolusMergeLock) {
                when (bolusTracker.current?.state) {
                    BolusState.QUEUED, BolusState.UNRESOLVED -> bolusTracker.onWritten()
                    else                                     -> null
                }
            }
            if (action != null) executeBolusAction(action)
            else aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS amount re-posted in state ${bolusTracker.current?.state}, not a new attempt")
        }
    }

    /** T13: the pump answered Running after the last start/stop was written. */
    fun isRunningConfirmedAfterLastStartStop(): Boolean =
        lastStatus == MedLinkPumpRunningState.Running && lastStatusSet > lastStartStopSent

    fun setLastPumpStatus(status: MedLinkPumpRunningState) {
        val previous = lastStatus
        this.lastStatus = status
        this.lastStatusSet = System.currentTimeMillis()
        val suspended = status == MedLinkPumpRunningState.Suspended
        val wasSuspended = previous == MedLinkPumpRunningState.Suspended
        val known = previous == MedLinkPumpRunningState.Running || previous == MedLinkPumpRunningState.Bolusing || wasSuspended
        if (known && status != MedLinkPumpRunningState.Unknow && suspended != wasSuspended) onCommandEffect("pump $previous -> $status")
        if (status == MedLinkPumpRunningState.Running) startFailures = 0
        if (suspended) stopFailures = 0
    }

    /** T13: failed START attempts in a row; reset when the pump answers Running. */
    private var startFailures = 0

    /**
     * T13 / bug 4: START was written and the pump is still suspended (e.g. a message on the pump screen).
     * The executor leaves the queues now and is re-sent later with alarm, instead of being dropped silently.
     */
    fun onStartFailed(reason: String) {
        // START confirmed and answered, pump still suspended: lock evidence (a one-off failure also happens)
        onCommandWithoutEffect("START: $reason")
        val executor = currentCommand
        lastCharacteristic = ""
        commandQueueBusy = CommandQueueBusy(false)
        if (executor != null) orderedQueues.forEach { it.remove(executor) }
        currentCommand = null
        scheduleStartRetry(executor, reason)
        nextCommand()
    }

    private fun scheduleStartRetry(executor: CommandExecutor<*>?, reason: String) {
        startFailures++
        val failedAt = System.currentTimeMillis()
        val decision = StartRetryRule.onFailed(startFailures)
        aapsLogger.warn(LTag.PUMPBTCOMM, "START failed ($reason), failure $startFailures: $decision")
        when (decision) {
            is StartRetryRule.Decision.Retry -> {
                if (decision.alarm) raiseBolusAlarm(
                    "Pump did not resume after START ($reason). Check the pump screen for a message. Retrying in ${decision.delayMs / 1000} s."
                )
                if (executor != null) handler.postDelayed({ retryStart(executor, failedAt) }, decision.delayMs)
            }

            StartRetryRule.Decision.GiveUp   -> {
                startFailures = 0
                raiseBolusAlarm("Pump is still SUSPENDED after ${StartRetryRule.MAX_FAILURES} START attempts ($reason). Resume the pump manually.")
            }
        }
    }

    private fun retryStart(executor: CommandExecutor<*>, failedAt: Long) {
        if (!StartRetryRule.shouldStillRetry(failedAt, lastStartStopSent, isRunningConfirmedAfterLastStartStop())) {
            aapsLogger.info(LTag.PUMPBTCOMM, "START retry dropped: newer start/stop sent or pump already running")
            startFailures = 0
            return
        }
        if (orderedQueues.any { q -> q.any { it.secondCommand() == MedLinkCommandType.StartPump } }) {
            aapsLogger.info(LTag.PUMPBTCOMM, "START retry dropped: another START is queued")
            return
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "START retry $startFailures")
        executor.clearExecutedCommand()
        executor.resetRetries()
        priorityExecutionCommandQueue.add(executor)
        if (connectionStatus == ConnectionStatus.CLOSED) medLinkConnect() else nextCommand()
    }

    /** Failed STOP attempts in a row; reset when the pump answers Suspended. */
    private var stopFailures = 0

    /**
     * STOP was written and confirmed and the pump still runs (usually a message on the pump screen, sometimes
     * a one-off failure). Like START (T13) the executor leaves the queues and is re-sent later, at most
     * [StartRetryRule.MAX_FAILURES] times; dropped when a newer start/stop was sent or the pump is suspended.
     */
    fun onStopFailed(reason: String) {
        onCommandWithoutEffect("STOP: $reason")
        val executor = currentCommand
        lastCharacteristic = ""
        commandQueueBusy = CommandQueueBusy(false)
        if (executor != null) orderedQueues.forEach { it.remove(executor) }
        currentCommand = null
        stopFailures++
        val failedAt = System.currentTimeMillis()
        val decision = StartRetryRule.onFailed(stopFailures)
        aapsLogger.warn(LTag.PUMPBTCOMM, "STOP failed ($reason), failure $stopFailures: $decision")
        when (decision) {
            is StartRetryRule.Decision.Retry -> {
                if (decision.alarm) raiseBolusAlarm(
                    "Pump did not suspend after STOP ($reason). Check the pump screen for a message. Retrying in ${decision.delayMs / 1000} s."
                )
                if (executor != null) handler.postDelayed({ retryStop(executor, failedAt) }, decision.delayMs)
            }

            StartRetryRule.Decision.GiveUp   -> {
                stopFailures = 0
                raiseBolusAlarm("Pump is still RUNNING after ${StartRetryRule.MAX_FAILURES} STOP attempts ($reason). Suspend the pump manually if needed.")
            }
        }
        nextCommand()
    }

    private fun retryStop(executor: CommandExecutor<*>, failedAt: Long) {
        val suspendedAfter = lastStatus == MedLinkPumpRunningState.Suspended && lastStatusSet > lastStartStopSent
        if (!StartRetryRule.shouldStillRetry(failedAt, lastStartStopSent, suspendedAfter)) {
            aapsLogger.info(LTag.PUMPBTCOMM, "STOP retry dropped: newer start/stop sent or pump already suspended")
            stopFailures = 0
            return
        }
        if (orderedQueues.any { q -> q.any { it.secondCommand() == MedLinkCommandType.StopPump } }) {
            aapsLogger.info(LTag.PUMPBTCOMM, "STOP retry dropped: another STOP is queued")
            return
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "STOP retry $stopFailures")
        executor.clearExecutedCommand()
        executor.resetRetries()
        priorityExecutionCommandQueue.add(executor)
        if (connectionStatus == ConnectionStatus.CLOSED) medLinkConnect() else nextCommand()
    }
}
