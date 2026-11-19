package app.aaps.pump.medtronic

import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.ui.compose.icons.IcLoopPausedPump
import app.aaps.core.data.model.RM
import app.aaps.pump.common.hw.medlink.bolus.MedLinkClock
import app.aaps.pump.common.hw.medlink.connection.MedLinkConnectionAlarmRule
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import app.aaps.core.interfaces.aps.Loop
import app.aaps.pump.medtronic.data.MedLinkResumeRule
import app.aaps.pump.medtronic.data.MedLinkZeroTbrRule
import app.aaps.pump.common.hw.medlink.bolus.MedLinkLoopBolusMerge
import app.aaps.pump.medtronic.data.MedLinkSentBolusKinds
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.MedLinkRunningTempBasal
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.mapState
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.ui.compose.icons.IcPluginMedtronic
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.keys.interfaces.withActivity
import app.aaps.pump.common.hw.medlink.bolus.MedLinkBolusPhase
import app.aaps.pump.common.hw.medlink.bolus.MedLinkBolusState
import app.aaps.pump.common.hw.medlink.bolus.MedLinkManualBolusRule
import app.aaps.pump.medtronic.dialog.MedLinkBolusConfirmActivity
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.SmbIntervalGate
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpTempBasalType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpBlock
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.MedLinkProfileParser
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.actions.CustomAction
import app.aaps.core.interfaces.pump.MedLinkStopOrigin
import app.aaps.core.interfaces.pump.actions.CustomActionType
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshButtonState
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.source.BgSync
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.utils.DateTimeUtil
import app.aaps.pump.common.MedLinkPumpPluginAbstract
import app.aaps.pump.common.data.PumpStatus
import app.aaps.pump.common.defs.PumpDriverState
import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.common.dialog.MedLinkBLEConfigActivity
import app.aaps.pump.common.hw.medlink.PumpResponses
import app.aaps.pump.common.hw.medlink.activities.BolusCallback
import app.aaps.pump.common.hw.medlink.activities.BolusDeliverCallback
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.ble.CommandPriority
import app.aaps.pump.common.hw.medlink.ble.command.BleBolusCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleBolusStatusCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleCalibrateCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleConnectCommand
import app.aaps.pump.common.hw.medlink.ble.command.BlePartialCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleStartCommand
import app.aaps.pump.common.hw.medlink.ble.command.BleStopCommand
import app.aaps.pump.common.hw.medlink.ble.data.BasalMedLinkMessage
import app.aaps.pump.common.hw.medlink.ble.data.BolusMedLinkMessage
import app.aaps.pump.common.hw.medlink.ble.data.BolusStatusMedLinkMessage
import app.aaps.pump.common.hw.medlink.ble.data.CalibrateMedLinkMessage
import app.aaps.pump.common.hw.medlink.ble.data.CommandStructure
import app.aaps.pump.common.hw.medlink.ble.data.MedLinkPumpMessage
import app.aaps.pump.common.hw.medlink.data.BolusAnswer
import app.aaps.pump.common.hw.medlink.data.MedLinkPartialBolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpDevice
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.pump.common.hw.medlink.keys.MedLinkBooleanPreferenceKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkDoubleKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkIntentPreferenceKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkLongKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkStringKey
import app.aaps.pump.common.hw.medlink.keys.MedLinkStringPreferenceKey
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser
import app.aaps.pump.common.hw.medlink.service.tasks.MedLinkWakeAndTuneTask
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkPumpInfo
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import app.aaps.pump.common.sync.PumpDbEntryTBR
import app.aaps.pump.common.sync.PumpSyncStorage
import app.aaps.pump.medtronic.comm.activities.BGHistoryCallback
import app.aaps.pump.medtronic.comm.activities.BasalCallback
import app.aaps.pump.medtronic.comm.activities.BolusHistoryCallback
import app.aaps.pump.medtronic.comm.activities.ChangeStatusCallback
import app.aaps.pump.medtronic.comm.activities.IsigHistoryCallback
import app.aaps.pump.medtronic.comm.activities.MedLinkCalibrationArgCallback
import app.aaps.pump.medtronic.comm.activities.MedLinkCalibrationCallback
import app.aaps.pump.medtronic.comm.activities.StatusCallback
import app.aaps.pump.medtronic.comm.history.pump.PumpHistoryEntry
import app.aaps.pump.medtronic.data.EnliteInterval
import app.aaps.pump.medtronic.data.MedLinkMedtronicHistoryData
import app.aaps.pump.medtronic.data.MedLinkProfileReadRule
import app.aaps.pump.medtronic.data.MedLinkStatusReadRule
import app.aaps.pump.medtronic.data.MedLinkStopPendingRule
import app.aaps.pump.medtronic.data.MedLinkTbrKeepRule
import app.aaps.pump.medtronic.data.MedLinkPumpProfileDiff
import app.aaps.pump.medtronic.data.MedLinkSuspendBatteryRule
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.BasalProfileEntry
import app.aaps.pump.medtronic.data.dto.ClockDTO
import app.aaps.pump.medtronic.data.dto.MedLinkModelParser
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusDTO
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair
import app.aaps.pump.medtronic.data.dto.TempBasalMicrobolusOperations
import app.aaps.pump.medtronic.data.MedLinkBasalPercent
import app.aaps.pump.medtronic.data.dto.TempBasalPair
import app.aaps.pump.medtronic.defs.BasalProfileStatus
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.medtronic.defs.MedLinkMedtronicStatusRefreshType
import app.aaps.pump.medtronic.defs.MedtronicCustomActionType
import app.aaps.pump.medtronic.defs.MedtronicNotificationType
import app.aaps.pump.medtronic.driver.MedLinkMedtronicPumpStatus
import app.aaps.pump.medtronic.events.EventMedtronicPumpValuesChanged
import app.aaps.pump.medtronic.keys.MedtronicBooleanPreferenceKey
import app.aaps.pump.medtronic.keys.MedtronicIntPreferenceKey
import app.aaps.pump.medtronic.keys.MedLinkMedtronicBooleanPreferenceKey
import app.aaps.pump.medtronic.keys.MedLinkMedtronicIntPreferenceKey
import app.aaps.pump.medtronic.keys.MedLinkMedtronicDoublePreferenceKey
import app.aaps.pump.medtronic.data.MedLinkPostBolusMinBasal
import app.aaps.pump.medtronic.data.MedLinkSuspensionPlan
import app.aaps.pump.medtronic.data.MedLinkTbrPlanCarry
import app.aaps.pump.medtronic.keys.MedtronicLongNonKey
import app.aaps.pump.medtronic.keys.MedtronicStringPreferenceKey
import app.aaps.pump.medtronic.service.MedLinkMedtronicService
import app.aaps.pump.medtronic.util.MedLinkMedtronicUtil
import com.google.gson.Gson
import info.nightscout.androidaps.plugins.pump.medtronic.comm.activities.ProfileCallback
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant
import org.joda.time.LocalDateTime
import org.joda.time.Seconds
import org.json.JSONObject
import java.lang.Math.round
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Arrays
import java.util.Calendar
import java.util.Date
import java.util.EnumMap
import java.util.GregorianCalendar
import java.util.LinkedList
import java.util.Locale
import java.util.Optional
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream
import java.util.stream.Stream.of
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Created by dirceu on 10.07.2020.
 */
@Singleton
open class MedLinkMedtronicPumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rxBus: RxBus?,
    context: Context?,
    resourceHelper: ResourceHelper?,
    preferences: Preferences,
    var activePlugin: ActivePlugin?,
//    override val sp: SP?,
    commandQueue: CommandQueue?,
    fabricPrivacy: FabricPrivacy?,
    private val medtronicUtil: MedLinkMedtronicUtil,
    private val medtronicHistoryData: MedLinkMedtronicHistoryData,
    private val medLinkServiceData: MedLinkServiceData,
    private val serviceTaskExecutor: ServiceTaskExecutor,
    private val receiverStatusStore: ReceiverStatusStore,
    uiInteract: UiInteraction,
    dateUtil: DateUtil?,
    aapsSchedulers: AapsSchedulers?,
    pumpSync: PumpSync?,
    pumpSyncStorage: PumpSyncStorage?,
    private val bgSync: BgSync,
    decimalFormatter: DecimalFormatter,
    private val parser: MedLinkProfileParser<MedLinkStandardReturn<MedLinkMedtronicDeviceType>, BasalProfile>,
    pumpEnactResultProvider: Provider<PumpEnactResult>,
    override val pumpStatusData: PumpStatus,
    override val medLinkPumpStatus: MedLinkMedtronicPumpStatus,
    bolusProgressData: BolusProgressData,
    private val notificationManager: NotificationManager,
    ) : MedLinkPumpPluginAbstract(
    PluginDescription() //
        .mainType(PluginType.PUMP) //
        .fragmentClass(MedLinkMedtronicFragment::class.java.name) //
        .icon(IcPluginMedtronic)
        .pluginName(R.string.medtronic_name)
        .shortName(R.string.medlink_medtronic_name_short) //
        .description(R.string.description_pump_medtronic_medlink),
    listOf(
        MedLinkBooleanPreferenceKey::class.java,
        MedLinkDoubleKey::class.java,
        MedLinkIntentPreferenceKey::class.java,
        MedLinkLongKey::class.java,
        MedLinkStringKey::class.java,
        MedLinkStringPreferenceKey::class.java,
        MedtronicBooleanPreferenceKey::class.java,
        MedtronicIntPreferenceKey::class.java,
        MedLinkMedtronicBooleanPreferenceKey::class.java,
        MedLinkMedtronicIntPreferenceKey::class.java,
        MedLinkMedtronicDoublePreferenceKey::class.java,
        MedtronicLongNonKey::class.java,
        MedtronicStringPreferenceKey::class.java,

        ),//
    PumpType.MEDLINK_MEDTRONIC_554_754_VEO,  // we default to most basic model, correct model from config is loaded later
    resourceHelper!!,
    aapsLogger,
    commandQueue!!,
    preferences,
    rxBus!!,
    context!!,
    fabricPrivacy!!,
    dateUtil!!,
    aapsSchedulers!!,
    pumpSync!!,
    pumpSyncStorage!!,
    decimalFormatter,
    uiInteract,
    pumpEnactResultProvider,
    bolusProgressData
), Pump, MedLinkPumpDevice {

    private var lastSMBBolus = 0L

    /** AAPS 4.0: queue commands are suspend functions; MedLink's fire-and-forget calls (status loop, user suspend) run here. */
    private val medLinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var medLinkMedtronicService: MedLinkMedtronicService? = null

    override val medLinkService: MedLinkMedtronicService? get() = medLinkMedtronicService

    private var lastHistoryCount = 0
    private var changeStatusTimestamp: Long = 0
    private lateinit var remainingBolus: DetailedBolusInfo
    private var bolusHistoryCheck: Boolean = false
    private var hasFirstAlert: Boolean = false
    private var hasSecondAlert: Boolean = false
    var lastCalibration: ZonedDateTime? = null

    // 4x: post-bolus minimum basal and the start (suspended/active) of temp basals below 100%
    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: dagger.Lazy<app.aaps.core.interfaces.iob.GlucoseStatusProvider>

    // Accept the profile read from the pump (setting "Accept profile changes from the pump")
    @Inject lateinit var profileFunction: dagger.Lazy<ProfileFunction>
    @Inject lateinit var profileRepository: dagger.Lazy<ProfileRepository>

    // T21: run the loop again at once after the user answers about the 0 % basal
    @Inject lateinit var loop: dagger.Lazy<Loop>

    /** 4y: last low battery shown, so a dismissed message is not shown again until the battery changes or recovers. */
    private var lowBatteryShown: MedLinkSuspendBatteryRule.Low? = null

    /** 4y: true (and the red status bar message) when the MedLink or the phone battery is at or below the minimum. */
    private fun lowBatteryForSuspend(minimum: Int): MedLinkSuspendBatteryRule.Low? =
        MedLinkSuspendBatteryRule.low(
            phoneLevel = receiverStatusStore.batteryLevel.takeIf { it > 0 }, // 0 = not read yet
            medLinkLevel = medLinkPumpStatus.medLinkBatteryRemaining,
            minimum = minimum
        )

    private fun batteryDeviceName(low: MedLinkSuspendBatteryRule.Low): String = rh.gs(
        if (low.device == MedLinkSuspendBatteryRule.Device.MEDLINK) R.string.medlink_battery_device_medlink
        else R.string.medlink_battery_device_phone
    )

    private fun suspendBlockedByLowBattery(): Boolean {
        val minimum = preferences.get(MedLinkMedtronicIntPreferenceKey.MinBatteryForSuspend)
        val low = lowBatteryForSuspend(minimum)
        if (low == null) {
            if (lowBatteryShown != null) notificationManager.dismiss(NotificationId.MEDLINK_SUSPEND_LOW_BATTERY)
            lowBatteryShown = null
            return false
        }
        aapsLogger.warn(LTag.PUMP, "MedLink: suspension blocked, ${low.device} battery ${low.level}% (minimum $minimum%)")
        if (low != lowBatteryShown) {
            lowBatteryShown = low
            val device = batteryDeviceName(low)
            notificationManager.post(
                NotificationId.MEDLINK_SUSPEND_LOW_BATTERY,
                rh.gs(R.string.medlink_suspend_low_battery, device, low.level, minimum)
            )
        }
        return true
    }

    /** Runs a TBR bolus step of the plan (skipped when too soon after the last TBR or manual bolus). */
    private fun runTbrBolusStep(oper: TempBasalMicroBolusPair) {
        if (tbrBolusTooSoon(oper)) {
            // not sent and not tried again: the next TBR bolus of the plan follows its own time
            tempBasalMicrobolusOperations.complete(oper)
        } else {
            if (PumpRunningState.TempBasalSuspended == medLinkPumpStatus.pumpRunningState) {
                reactivatePump(oper) { o: Any? -> o }
            }
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.lastKnownBolusTime =
                if (medLinkPumpStatus.lastBolusTime != null) medLinkPumpStatus.lastBolusTime!!.time else if (pumpSyncStorage.pumpSyncStorageBolus.isNotEmpty()) {
                    val bolusList = pumpSyncStorage.pumpSyncStorageBolus
                    bolusList.sortBy { it.date }
                    bolusList[0].date
                } else {
                    System.currentTimeMillis()
                }
            detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
            detailedBolusInfo.insulin = oper.dose
            detailedBolusInfo.bolusType = BS.Type.TBR
            detailedBolusInfo.bolusTimestamp = System.currentTimeMillis()
            aapsLogger.debug(LTag.APS, "applyAPSRequest: bolus()")
            val callback = { o: PumpEnactResult? ->
                aapsLogger.info(LTag.PUMPBTCOMM, "temp basal bolus $o")
                if (o?.success == true) {
                    tempBasalMicrobolusOperations.complete(oper)
                } else {
                    oper.isCommandIssued = false
                }
                Unit
            }
            deliverTreatment(detailedBolusInfo, callback)
        }
    }

    /**
     * A TBR bolus step due in the next [TBR_JOIN_SMB_MS] is sent now, right after an SMB was queued: the BLE queue joins
     * it into the SMB (one pump bolus, one connection, recorded as one SMB and one TBR part). Field log 2026-11-18 10:50:
     * the TBR bolus was due 24 s after the SMB was written and had to wait. The TBR insulin comes at most 60 s early.
     */
    private fun joinDueTbrBolusStep() {
        val oper = tempBasalMicrobolusOperations.takeDueBolusStep(LocalDateTime.now().plusMillis(TBR_JOIN_SMB_MS.toInt())) ?: return
        aapsLogger.info(LTag.PUMP, "TBR bolus ${oper.dose}U due ${oper.releaseTime.toString("HH:mm:ss")}: sent now with the SMB")
        runTbrBolusStep(oper)
    }

    /**
     * A TBR bolus is not sent when the last TBR bolus or the last manual bolus was less than the SMB interval ago.
     * SMBs do not count: TBR boluses and SMBs are both automatic, they have their own interval each.
     * The user gets an info message, no alarm.
     */
    private fun tbrBolusTooSoon(oper: TempBasalMicroBolusPair): Boolean {
        val interval = preferences.get(IntKey.ApsMaxSmbFrequency)
        val lastTbrBolusAt = runBlocking { persistenceLayer.getNewestBolusOfType(BS.Type.TBR) }?.timestamp ?: 0L
        val lastManualBolusAt = runBlocking { persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL) }?.timestamp ?: 0L
        val lastBolusAt = maxOf(lastTbrBolusAt, lastManualBolusAt)
        if (lastBolusAt == 0L) return false
        val now = System.currentTimeMillis()
        if (!SmbIntervalGate.tooSoon(lastBolusAt, interval, now)) return false
        val agoSeconds = (now - lastBolusAt) / 1000
        aapsLogger.info(LTag.PUMP, "TBR bolus ${oper.dose} U not sent: last TBR or manual bolus ${agoSeconds}s ago, SMB interval $interval min")
        notificationManager.post(
            NotificationId.MEDLINK_TBR_BOLUS_SKIPPED,
            rh.gs(R.string.medlink_tbr_bolus_skipped, oper.dose, agoSeconds, interval)
        )
        return true
    }

    /** 4x: a temp basal below the configured minimum after a manual bolus (BG rising, at or above target) is raised. */
    private fun postBolusMinBasal(requestedRate: Double, profile: Profile): Double {
        if (!preferences.get(MedLinkMedtronicBooleanPreferenceKey.MinBasalAfterBolusEnabled)) return requestedRate
        val minPercent = preferences.get(MedLinkMedtronicIntPreferenceKey.MinBasalAfterBolusPercent)
        if (minPercent <= 0) return requestedRate
        val glucose = glucoseStatusProvider.get().glucoseStatusData
        val decision = MedLinkPostBolusMinBasal.decide(
            requestedRate = requestedRate,
            baseRate = medLinkBaseBasalRate.takeIf { it > 0.0 } ?: profile.getBasal(),
            settings = MedLinkPostBolusMinBasal.Settings(
                minPercent = minPercent,
                windowMinutes = preferences.get(MedLinkMedtronicIntPreferenceKey.MinBasalAfterBolusMinutes),
                minDelta = preferences.get(MedLinkMedtronicDoublePreferenceKey.MinBasalAfterBolusDelta)
            ),
            lastManualBolusAt = runBlocking { persistenceLayer.getNewestBolusOfType(app.aaps.core.data.model.BS.Type.NORMAL) }?.timestamp,
            now = System.currentTimeMillis(),
            delta = glucose?.delta,
            bg = glucose?.glucose,
            targetMgdl = profile.getTargetMgdl()
        )
        if (decision.rate != requestedRate) aapsLogger.info(LTag.PUMP, "MedLink post-bolus minimum basal: $requestedRate -> ${decision.rate} U/h (${decision.reason})")
        else aapsLogger.debug(LTag.PUMP, "MedLink post-bolus minimum basal not applied: ${decision.reason}")
        return decision.rate
    }

    // private override var dateUtil: DateUtil? = null
    // AAPS 4.0: pump values are StateFlows; MedLink reads them from its own status (not the RileyLink Medtronic one)
    override val reservoirLevel: StateFlow<PumpInsulin> by lazy { medLinkPumpStatus.reservoirRemainingUnitsFlow.mapState(::PumpInsulin) }
    // "Battery info" = age: no level, so the main screen shows the battery age (last battery change) and not a %.
    // mapState runs the check on every read, so a change of the setting shows at once.
    override val batteryLevel: StateFlow<Int?> by lazy { medLinkPumpStatus.batteryRemainingFlow.mapState { if (isBatteryAge()) null else it } }
    override val lastBolusAmount: StateFlow<PumpInsulin?> by lazy { medLinkPumpStatus.lastBolusAmountFlow.mapState { it?.let(::PumpInsulin) } }


    private var batteryLastRead = 0L
    private var lastBatteryLevel = 0
    private var batteryDelta = 0.0

    private var missedBGs = 5
    private var firstMissedBGTimestamp = 0L

    // variables for handling statuses and history
    // firstRun, isRefresh, hasTimeDateOrTimeZoneChanged: inherited from PumpPluginAbstract (AAPS 3.4.2.2)
    private val medLinkStatusRefreshMap: MutableMap<MedLinkMedtronicStatusRefreshType, Long> =
        EnumMap(MedLinkMedtronicStatusRefreshType::class.java)
    private var isInitialized = false
    private val lastPumpHistoryEntry: PumpHistoryEntry? = null
    private val busyTimestamps: MutableList<Long> = ArrayList()
    /** Duration of the last suspension plan (TBR below the basal); used by createTemporaryBasalData when it gets 0. */
    private var durationInMinutes: Int? = null
    var tempBasalMicrobolusOperations: TempBasalMicrobolusOperations =
        TempBasalMicrobolusOperations()
    // Never set: always null (old code still reads it). B6 removed its dead check from the profile read path.
    private val profile: Profile? = null
    private var percent: Int? = null
    private var result: PumpEnactResult? = null
    private var bolusDeliveryType = BolusDeliveryType.Idle
    private var customActions: List<CustomAction>? = null
    private var lastStatus = PumpRunningState.Running
    protected var lastBGHistoryRead = 0L
    var basalProfile: BasalProfile? = null
    private var lastPreviousHistory = 0L
    private var lastTryToConnect = 0L
    private var lastBgs: Array<Int?>? = null
    private var bgIndex = 0
    private var lastBolusHistoryRead = 0L
    private var pumpTimeDelta = 0L
    private var lastDetailedBolusInfo: DetailedBolusInfo? = null

    /** The boluses this app sent (type, amount, time): the pump history gives every bolus as NORMAL. */
    private var sentBoluses: List<MedLinkSentBolusKinds.Sent> = emptyList()

    private fun rememberSentBolus(type: BS.Type, amount: Double) = synchronized(this) {
        val now = System.currentTimeMillis()
        sentBoluses = MedLinkSentBolusKinds.prune(sentBoluses, now) + MedLinkSentBolusKinds.Sent(type, amount, now)
    }

    private fun joinedSentBolusFor(amount: Double, historyTime: Long): List<MedLinkSentBolusKinds.Sent>? = synchronized(this) {
        MedLinkSentBolusKinds.matchJoined(amount, historyTime, sentBoluses)
    }

    private fun sentBolusFor(amount: Double, historyTime: Long): MedLinkSentBolusKinds.Sent? = synchronized(this) {
        sentBoluses = MedLinkSentBolusKinds.prune(sentBoluses, System.currentTimeMillis())
        MedLinkSentBolusKinds.match(amount, historyTime, sentBoluses)
    }
    private var late1Min = false


    var lastProfileRead: Long = 0
    override fun applyBasalConstraints(
        absoluteRate: Constraint<Double>,
        profile: Profile
    ): Constraint<Double> {
        // T21: no TBR above 100 % until the user answers about the 0 % basal
        if (zeroTbrLimitActive()) absoluteRate.setIfSmaller(profile.getBasal(), rh.gs(R.string.medlink_zero_tbr_waiting), this)
        return absoluteRate
    }

    override fun applyBasalPercentConstraints(percentRate: Constraint<Int>, profile: Profile): Constraint<Int> {
        if (zeroTbrLimitActive()) percentRate.setIfSmaller(100, rh.gs(R.string.medlink_zero_tbr_waiting), this)
        return percentRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        // T21: no SMB until the user answers about the 0 % basal
        if (zeroTbrLimitActive()) value.set(false, rh.gs(R.string.medlink_zero_tbr_waiting), this)
        return value
    }

    override fun userSuspend(callback: Callback?) {
        aapsLogger.info(LTag.PUMP, "MedtronicPumpPlugin::userSuspend")
        stopPump(callback ?: object : Callback() {
            override fun run() {}
        }, MedLinkStopOrigin.USER)
    }

    /** T23: phone time of the last STOP / START that the pump confirmed (its answer also gives the pump state). */
    @Volatile private var lastStartStopAnswerAt = 0L

    /** B18 ([MedLinkStopPendingRule]): phone time when a STOP was queued and its answer did not come yet; 0 = none. */
    @Volatile private var stopQueuedAt = 0L

    /**
     * B18: true when a "safety" STOP (pump runs while the TBR is 0 %) must not be queued, because another STOP is
     * still on its way. Never used for the STOP of a plan step.
     */
    private fun skipSafetyStop(): Boolean {
        if (!MedLinkStopPendingRule.isStopPending(dateUtil.now(), stopQueuedAt)) return false
        aapsLogger.info(LTag.PUMP, "B18: a STOP queued ${(dateUtil.now() - stopQueuedAt) / 1000}s ago has no answer yet, no second STOP")
        return true
    }

    private fun stopPump(callback: Callback, origin: MedLinkStopOrigin = MedLinkStopOrigin.AUTOMATIC) {
        aapsLogger.info(LTag.PUMP, "MedtronicPumpPlugin::stopPump - $origin")
        aapsLogger.info(LTag.PUMP, "batteryDelta $batteryDelta")
        // 4y: no suspension with the MedLink or the phone battery low (nobody would start the pump again);
        // a red status bar message instead of an alarm dialog
        if (suspendBlockedByLowBattery()) {
            // val i = Intent(context, ErrorHelperActivity::class.java)
            // i.putExtra("soundid", R.raw.boluserror)
            // i.putExtra(
            //     "status",
            //     rh.gs(string.medlink_medtronic_cmd_stop_could_not_be_delivered)
            // )
            // i.putExtra("title", rh.gs(string.medtronic_errors))
            // i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // context.startActivity(i)
            return
        }
        if (origin == MedLinkStopOrigin.USER || PumpRunningState.Suspended != medLinkPumpStatus.pumpRunningState || medLinkPumpStatus.pumpRunningState == PumpRunningState.TempBasalSuspended) {
            val function = ChangeStatusCallback(
                aapsLogger,
                ChangeStatusCallback.OperationType.STOP, this
            )
            val startStopFunction = function.andThen { f: MedLinkStandardReturn<PumpDriverState> ->
                stopQueuedAt = 0L // B18: the STOP got its answer
                result = when {
                    f.functionResult === PumpDriverState.Suspended -> {
                        lastStartStopAnswerAt = dateUtil.now()
                        // B12: only an automatic stop may be undone by the app (MedLinkResumeRule); kept over a restart
                        preferences.put(MedtronicLongNonKey.MedLinkAutoStopAt, if (origin == MedLinkStopOrigin.AUTOMATIC) dateUtil.now() else 0L)
                        pumpEnactResultProvider.get().success(true).enacted(true)
                    }

                    f.functionResult === PumpDriverState.Initialized -> {
                        pumpEnactResultProvider.get().success(false).enacted(true)
                    }

                    else -> {
                        pumpEnactResultProvider.get().success(false).enacted(false)
                    }
                }
                sendPumpUpdateEvent()
                callback.result(result!!).run()
                f
            }
            val message = MedLinkPumpMessage<PumpDriverState, Any>(
                MedLinkCommandType.StopStartPump,
                MedLinkCommandType.StopPump,
                startStopFunction,
                btSleepTime, BleStopCommand(
                    aapsLogger,
                    medLinkMedtronicService?.medLinkServiceData,
                    this,
                    origin
                ), CommandPriority.NORMAL
            )
            stopQueuedAt = dateUtil.now() // B18
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(message)
        }
    }

    val btSleepTime: Long
        get() {
            val sleepTime =
                preferences.get(MedtronicIntPreferenceKey.IntervalBetweenConnections)
            return sleepTime * 1000L
        }

    override fun storeCancelTempBasal() {
        aapsLogger.info(LTag.DATABASE, "stopping basal")
        // val state = pumpSync.expectedPumpState()
        // val timestamp = state?.temporaryBasal?.timestamp ?: dateUtil.now()

        runBlocking { pumpSync.syncStopTemporaryBasalWithPumpIdMedLink(
            dateUtil.now(),
            dateUtil.now(),
            pumpType,
            medLinkPumpStatus.serialNumber
        ) }
        // val runningTBR = pumpStatusData.runningTBR
        // when {
        //     runningTBR != null -> {
        //         val differenceTime = System.currentTimeMillis() - runningTBR
        //         //val tbrData = runningTBR
        //
        //         val result = pumpSync.syncTemporaryBasalWithPumpId(
        //             runningTBR.date,
        //             runningTBR.rate,
        //             differenceTime,
        //             runningTBR.isAbsolute,
        //             runningTBR.tbrType,
        //             runningTBR.pumpId!!,
        //             runningTBR.pumpType,
        //             runningTBR.serialNumber
        //         )
        //         val differenceTimeMin = floor(differenceTime / (60.0 * 1000.0))
        //
        //         aapsLogger.debug(
        //             LTag.PUMP, "canceling running TBR - syncTemporaryBasalWithPumpId [date=${runningTBR.date}, " +
        //                 "pumpId=${runningTBR.pumpId}, rate=${runningTBR.rate} U, duration=${differenceTimeMin.toInt()}, " +
        //                 "pumpSerial=${medLinkPumpStatus.serialNumber}] - Result: $result"
        //         )
        //     }
        // }
        // pumpSync.syncStopTemporaryBasalWithPumpId(
        //     timestamp = timestamp,
        //     endPumpId = dateUtil.now(),
        //     pumpType = pumpType ?: PumpType.GENERIC_AAPS,
        //     pumpSerial = serialNumber()
        // )
        medLinkPumpStatus.runningTBR = null
        medLinkPumpStatus.tempBasalAmount = (profile?.getBasal()
            ?: basalProfile?.getEntryForTime(Instant.now())?.rate)!!

    }

    fun startPump(callback: Callback?, clearStops: Boolean = false) {
        aapsLogger.info(LTag.PUMP, "MedtronicPumpPlugin::startPump - ")
        // T13: the app-side Running state is trusted only when the pump confirmed it after the last start/stop
        val runningConfirmed = medLinkMedtronicService?.medLinkBLE?.isRunningConfirmedAfterLastStartStop() ?: false
        if (medLinkPumpStatus.pumpRunningState !== PumpRunningState.Running || !runningConfirmed) {
            // buildStartStopCommands()
            val activity: Function<Supplier<Stream<String>>, MedLinkStandardReturn<PumpDriverState>> =
                ChangeStatusCallback(aapsLogger, ChangeStatusCallback.OperationType.START, this)
                    // }
                    .andThen { f: MedLinkStandardReturn<PumpDriverState> ->
                        result = when {
                            f.functionResult === PumpDriverState.Initialized -> {
                                lastStartStopAnswerAt = dateUtil.now()
                                temporaryBasal
                                finishAction("TBR")
                                pumpEnactResultProvider.get().success(true).enacted(true)
                            }

                            f.functionResult === PumpDriverState.Suspended -> {
                                pumpEnactResultProvider.get().success(false).enacted(true)
                            }

                            else -> {
                                pumpEnactResultProvider.get().success(false).enacted(false)
                            }
                        }
                        sendPumpUpdateEvent()
                        callback?.result(result!!)?.run()
                        f
                    }

            val message = MedLinkPumpMessage<PumpDriverState, Any>(
                MedLinkCommandType.StopStartPump,
                MedLinkCommandType.StartPump,
                activity,
                btSleepTime,
                BleStartCommand(
                    aapsLogger, medLinkMedtronicService?.medLinkServiceData,
                    this
                ),
                CommandPriority.HIGH, clearStops
            )
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(message)
        }
    }

    fun alreadyRun() {
        firstRun = false
    }

    enum class StatusRefreshAction {
        Add,  //
        GetData
    }

    override val isFakingTempsByExtendedBoluses: Boolean
        get() = false

    // AAPS 4.0: updatePreferenceSummary (MedLink MAC address as summary) is gone with the View preferences

    override fun initPumpStatusData() {
        medLinkPumpStatus.lastConnection =
            preferences.get(MedLinkLongKey.LastGoodDeviceCommunicationTime)
        medLinkPumpStatus.lastDataTime = medLinkPumpStatus.lastConnection
        medLinkPumpStatus.previousConnection = medLinkPumpStatus.lastConnection

        //if (rileyLinkMedtronicService != null) rileyLinkMedtronicService.verifyConfiguration();
        aapsLogger.debug(LTag.PUMP, "initPumpStatusData: " + medLinkPumpStatus)

        // this is only thing that can change, by being configured
        pumpDescription.maxTempAbsolute =
            if (medLinkPumpStatus.maxBasal != null) medLinkPumpStatus.maxBasal!! else 35.0
        pumpDescription.tempBasalStyle = PumpDescription.PERCENT
        pumpDescription.tempPercentStep = 1

        // set first Medtronic Pump Start
        if (preferences.getIfExists(MedtronicLongNonKey.FirstPumpUse) == null) {
            preferences.put(MedtronicLongNonKey.FirstPumpUse, System.currentTimeMillis())
        }
        pumpSyncStorage.initStorage()

        migrateSettings()
    }

    private fun migrateSettings() {
        if (preferences.get(MedtronicStringPreferenceKey.PumpFrequency) == "US (916 MHz)")
            preferences.put(
                MedtronicStringPreferenceKey.PumpFrequency,
                pumpFreqValues[0].toString()
            )

        // val initC: Any = rh.gs(R.string.key_medlink_init_command)
        //        if(initC instanceof HashSet) {

        //        }else{
        //            this.initCommands= new HashSet<>();
        //        }

        //        String encoding = sp.getString(MedtronicConst.Prefs.Encoding, "RileyLink 4b6b Encoding");
        //
        //        if ("RileyLink 4b6b Encoding".equals(encoding)) {
        //            sp.putString(MedtronicConst.Prefs.Encoding, getRh().gs(R.string.key_medtronic_pump_encoding_4b6b_rileylink));
        //        }
        //
        //        if ("Local 4b6b Encoding".equals(encoding)) {
        //            sp.putString(MedtronicConst.Prefs.Encoding, getRh().gs(R.string.key_medtronic_pump_encoding_4b6b_local));
        //        }
    }

    override fun onStartScheduledPumpActions() {
        // check status every minute (if any status needs refresh we send readStatus command)
        Thread {
            do {
                SystemClock.sleep(5000)
                if (this.isInitialized) {
                    val statusRefresh = workWithStatusRefresh(
                        StatusRefreshAction.GetData, null, 0L
                    )
                    if (doWeHaveAnyStatusNeededRefreshing(statusRefresh)) {
                        if (!commandQueue.statusInQueue()) {
                            medLinkScope.launch { commandQueue.readStatus("Scheduled Status Refresh") }
                        }
                    }
                    if (System.currentTimeMillis() - medLinkPumpStatus.lastConnection > 590000) {
                        readPumpData(false)
                    }
                    clearBusyQueue()
                }
            } while (serviceRunning)
        }.start()
    }

    @Synchronized
    private fun clearBusyQueue() {
        if (busyTimestamps.size == 0) {
            return
        }
        val deleteFromQueue: MutableSet<Long> = HashSet()
        for (busyTimestamp in busyTimestamps) {
            if (System.currentTimeMillis() > busyTimestamp) {
                deleteFromQueue.add(busyTimestamp)
            }
        }
        if (deleteFromQueue.size == busyTimestamps.size) {
            busyTimestamps.clear()
            setEnableCustomAction(MedtronicCustomActionType.ClearBolusBlock, false)
        }
        if (deleteFromQueue.size > 0) {
            busyTimestamps.removeAll(deleteFromQueue)
        }
    }

    override val serviceClass: Class<*>
        get() = MedLinkMedtronicService::class.java

    private var isServiceSet: Boolean = false

    override fun isInitialized(): Boolean {
        if (displayConnectionMessages) aapsLogger.debug(
            LTag.PUMP,
            "MedtronicPumpPlugin::isInitialized"
        )
        return isServiceSet && isInitialized
    }

    //TODO implement
    override fun isSuspended(): Boolean {
        // B12: read temporaryBasal once (it can change between two reads)
        val desiredPct = temporaryBasal?.desiredPct ?: return false
        return medLinkPumpStatus.pumpRunningState === PumpRunningState.Suspended && desiredPct <= 100
    }



    override fun isBusy(): Boolean {
        return isBusy
    }

    override fun setBusy(busy: Boolean) {}
    override fun triggerPumpConfigurationChangedEvent() {}


//    override fun getService(): DaggerService? {
//        return medLinkService
//    }

    //    @Override public MedLinkService getMedLinkService() {
    //        return medLinkService;
    //    }
    override fun isConnected(): Boolean {
        if (displayConnectionMessages) aapsLogger.debug(
            LTag.PUMP,
            "MedLinkMedtronicPumpPlugin::isConnected"
        )
        return isServiceSet && medLinkMedtronicService?.isInitialized == true
    }

    override fun isConnecting(): Boolean {
        if (displayConnectionMessages) aapsLogger.debug(
            LTag.PUMP,
            "MedtronicPumpPlugin::isConnecting"
        )
        return !isServiceSet || medLinkMedtronicService?.isInitialized == false
    }

    override fun finishHandshaking() {}
    override fun connect(reason: String) {
        //        medLinkService.getMedLinkRFSpy().e()
    }

    //
    //    @Override
    //    public void disconnect(String reason) {
    //    }
    //
    //    @Override
    //    public void stopConnecting() {
    //    }

    /** B11: a status read only when a refresh type or a TBR plan step is due (see [MedLinkStatusReadRule]). */
    private fun doWeHaveAnyStatusNeededRefreshing(statusRefresh: Map<MedLinkMedtronicStatusRefreshType?, Long?>?): Boolean {
        val steps = tempBasalMicrobolusOperations.operations.map {
            MedLinkStatusReadRule.Step(it.releaseTime.toDate().time, it.isCommandIssued)
        }
        return MedLinkStatusReadRule.needsRead(
            dateUtil.now(), statusRefresh?.values.orEmpty(), steps, hasTimeDateOrTimeZoneChanged
        )
    }

    private val lastPumpEntryTime: Long
        private get() {
            val lastPumpEntryTime = preferences.get(MedtronicLongNonKey.LastPumpHistoryEntry)
            return try {
                val localDateTime = DateTimeUtil.toLocalDateTime(lastPumpEntryTime)
                if (localDateTime.year != GregorianCalendar()[Calendar.YEAR]) {
                    aapsLogger.warn(
                        LTag.PUMP,
                        "Saved LastPumpHistoryEntry was invalid. Year was not the same."
                    )
                    return 0L
                }
                lastPumpEntryTime
            } catch (ex: Exception) {
                aapsLogger.warn(LTag.PUMP, "Saved LastPumpHistoryEntry was invalid.")
                0L
            }
        }

    private fun readPumpHistoryLogic() {

        val debugHistory = false
        var targetDate: LocalDateTime? = null

        if (lastPumpHistoryEntry == null) {
            if (debugHistory) aapsLogger.debug(
                LTag.PUMP,
                logPrefix + "readPumpHistoryLogic(): lastPumpHistoryEntry: null"
            )
            val lastPumpHistoryEntryTime = lastPumpEntryTime
            var timeMinus36h = LocalDateTime()
            timeMinus36h = timeMinus36h.minusHours(36)
            medtronicHistoryData.setIsInInit(true)
            if (lastPumpHistoryEntryTime == 0L) {
                if (debugHistory) aapsLogger.debug(
                    LTag.PUMP,
                    logPrefix + "readPumpHistoryLogic(): lastPumpHistoryEntryTime: 0L - targetDate: "
                            + targetDate
                )
                targetDate = timeMinus36h
            } else {
                // LocalDateTime lastHistoryRecordTime = DateTimeUtil.toLocalDateTime(lastPumpHistoryEntryTime);
                if (debugHistory) aapsLogger.debug(
                    LTag.PUMP,
                    logPrefix + "readPumpHistoryLogic(): lastPumpHistoryEntryTime: " + lastPumpHistoryEntryTime + " - targetDate: " + targetDate
                )
                medtronicHistoryData.setLastHistoryRecordTime(lastPumpHistoryEntryTime)
                var lastHistoryRecordTime = DateTimeUtil.toLocalDateTime(lastPumpHistoryEntryTime)
                lastHistoryRecordTime =
                    lastHistoryRecordTime.minusHours(12) // we get last 12 hours of history to
                // determine pump state
                // (we don't process that data), we process only
                if (timeMinus36h.isAfter(lastHistoryRecordTime)) {
                    targetDate = timeMinus36h
                }
                targetDate =
                    if (timeMinus36h.isAfter(lastHistoryRecordTime)) timeMinus36h else lastHistoryRecordTime
                if (debugHistory) aapsLogger.debug(
                    LTag.PUMP,
                    logPrefix + "readPumpHistoryLogic(): targetDate: " + targetDate
                )
            }
        } else {
            if (debugHistory) aapsLogger.debug(
                LTag.PUMP,
                logPrefix + "readPumpHistoryLogic(): lastPumpHistoryEntry: not null - " + medtronicUtil.gsonInstance.toJson(
                    lastPumpHistoryEntry
                )
            )
            medtronicHistoryData.setIsInInit(false)
            // medtronicHistoryData.setLastHistoryRecordTime(lastPumpHistoryEntry.atechDateTime);

            // targetDate = lastPumpHistoryEntry.atechDateTime;
        }

        //getAapsLogger().debug(LTag.PUMP, "HST: Target Date: " + targetDate);
        val msg = MedLinkPumpMessage<MedLinkPumpStatus, Any>(
            MedLinkCommandType.GetState,
            StatusCallback(
                aapsLogger, this,
                medLinkPumpStatus
            ),
            btSleepTime,
            BlePartialCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData),
            CommandPriority.NORMAL
        )
        medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)
        if (debugHistory) aapsLogger.debug(LTag.PUMP, "HST: After task")


        medtronicHistoryData.filterNewEntries()

        // determine if first run, if yes detrmine how much of update do we need
        // first run:
        // get last hiostory entry, if not there download 1.5 days of data
        // - there: check if last entry is older than 1.5 days
        // - yes: download 1.5 days
        // - no: download with last entry
        // - not there: download 1.5 days
        //
        // upload all new entries to NightScout (TBR, Bolus)
        // determine pump status
        //
        // save last entry
        //
        // not first run:
        // update to last entry
        // - save
        // - determine pump status
    }

    private fun readPumpBGHistory(force: Boolean) {
        if ((activePlugin?.activeBgSource as PluginBase).javaClass.canonicalName?.contains("MedLinkPlugin") == false) {
            return
        }
        //TODO fazer ele não ficar lendo o histórico quando o sensor estiver em warmup
        if (firstMissedBGTimestamp > 0L && System.currentTimeMillis() - lastBGHistoryRead > 610000 &&
            System.currentTimeMillis() - firstMissedBGTimestamp > 1800000 || missedBGs > 4 || force
        ) {
            lastBGHistoryRead = System.currentTimeMillis()
            val func = BGHistoryCallback(this, aapsLogger, false, false)
            val isigFunc = IsigHistoryCallback(this, aapsLogger, false, func)
            val msg = MedLinkPumpMessage<BgSync.BgHistory, BgSync.BgHistory>(
                MedLinkCommandType.BGHistory,   // MedLinkCommandType.IsigHistory,
                MedLinkCommandType.NoCommand,
                func,
                // isigFunc,
                btSleepTime,
                BlePartialCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData),
                CommandPriority.NORMAL
            )
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)
        }
    }

    private fun readPumpData(scheduled: Boolean, interval: Long = 300000L) {
        //        if (isLoggingEnabled())
        //            LOG.error(getLogPrefix() + "readPumpHistory WIP.");
        scheduleNextReadState(scheduled, interval)
        aapsLogger.info(LTag.CONFIGBUILDER, "read pump history")
        readPumpHistoryLogic()

        //        if (bgDelta == 1 || bgDelta > 5) {
        //            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory);
        //        } else if (bgDelta == 0) {
        //            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory, 1);
        //        } else {
        //            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory, -1 * bgDelta);
        //        }
        if (medtronicHistoryData.hasRelevantConfigurationChanged()) {
            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.Configuration, -1)
        }
        if (medtronicHistoryData.hasPumpTimeChanged()) {
            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpTime, -1)
        }
        if (medLinkPumpStatus.basalProfileStatus !== BasalProfileStatus.NotInitialized
            && medtronicHistoryData.hasBasalProfileChanged()
        ) {
            // medtronicHistoryData.processLastBasalProfileChange(
            //     pumpDescription.pumpType,
            //     medLinkPumpStatus
            // )
        }
        val previousState = pumpState
        if (medtronicHistoryData.isPumpSuspended()) {
            pumpState = PumpDriverState.Suspended
            aapsLogger.debug(LTag.PUMP, logPrefix + "isPumpSuspended: true")
        } else {
            if (previousState === PumpDriverState.Suspended) {
                pumpState = PumpDriverState.Ready
            }
            aapsLogger.debug(LTag.PUMP, logPrefix + "isPumpSuspended: false")
        }
        medtronicHistoryData.processNewHistoryData()
        medtronicHistoryData.finalizeNewHistoryRecords()
        // this.medtronicHistoryData.setLastHistoryRecordTime(this.lastPumpHistoryEntry.atechDateTime);
    }

    private fun scheduleNextReadState(scheduled: Boolean, interval: Long = 300000L): Int {
        if (medLinkPumpStatus.lastBGTimestamp != 0L) {
            var bgDelta = Math.toIntExact(
                (medLinkPumpStatus.lastDataTime -
                        medLinkPumpStatus.lastBGTimestamp) / 60000
            )
            if (isInitialized && scheduled) {
                if (bgDelta in 7..9) {
                    aapsLogger.info(LTag.PUMPBTCOMM, "reading failed increasing")
                    EnliteInterval.currentFailed()
                } else if (bgDelta < 4) {
                    EnliteInterval.currentSuccess(medLinkPumpStatus.lastConnection)
                }
            }
            pumpTimeDelta = medLinkPumpStatus.lastConnection -
                    medLinkPumpStatus.lastDataTime
            var minutesDelta = Math.toIntExact(TimeUnit.MILLISECONDS.toMinutes(pumpTimeDelta))
            //            if (calculateBGDeltaAvg(bgDelta) > 6.0) {
            //                minutesDelta++
            //            }
            if (aapsLogger != null) {
                aapsLogger.info(
                    LTag.CONFIGBUILDER,
                    "Last Connection" + medLinkPumpStatus.lastConnection
                )
                aapsLogger.info(LTag.CONFIGBUILDER, "Next Delta $minutesDelta")
                aapsLogger.info(LTag.CONFIGBUILDER, "bgdelta  $bgDelta")
                aapsLogger.info(
                    LTag.CONFIGBUILDER,
                    "Last connection " + dateUtil.toISONoZone(medLinkPumpStatus.lastConnection)
                )
                aapsLogger.info(
                    LTag.CONFIGBUILDER,
                    "Last bg " + dateUtil.toISONoZone(medLinkPumpStatus.lastBGTimestamp)
                )
                aapsLogger.info(LTag.CONFIGBUILDER, "Last bg " + EnliteInterval.toString())

            }
            //            while (bgDelta >= 5) {
            //                bgDelta -= 5
            //            }
            var lostContactDelta = 0
            if (medLinkPumpStatus.lastConnection - medLinkPumpStatus.lastBGTimestamp > 360000 || late1Min) {
                lostContactDelta = 2
            }
            if (hasStarted) {
                scheduleNextRefresh(
                    MedLinkMedtronicStatusRefreshType.PumpHistory,
                    pumpTimeDelta + EnliteInterval.nextInterval()
                )
            } else {
                scheduleNextRefresh(
                    MedLinkMedtronicStatusRefreshType.PumpHistory,
                    pumpTimeDelta + (System.currentTimeMillis() -
                            medLinkPumpStatus.lastConnection) + 300000
                )
                if (medLinkPumpStatus.lastConnection > 0L) {
                    hasStarted = true
                }
            }
        } else {
            aapsLogger.info(LTag.CONFIGBUILDER, "scheduling")
            scheduleNextRefresh(
                MedLinkMedtronicStatusRefreshType.PumpHistory,
                300000
            )
        }
        return 0
    }

    private fun calculateBGDeltaAvg(bgDelta: Int): Double {
        if (lastBgs == null) {
            lastBgs = arrayOfNulls(5)
            Arrays.fill(lastBgs, 0)
        }
        bgIndex++
        if (bgIndex == 5) {
            bgIndex = 0
        }
        lastBgs!![bgIndex] = bgDelta
        var sum = 0.0
        for (value in lastBgs!!) {
            sum += value!!.toDouble()
        }
        return sum / lastBgs!!.size
    }

    protected open fun refreshAnyStatusThatNeedsToBeRefreshed() {
        val statusRefresh = workWithStatusRefresh(
            StatusRefreshAction.GetData, null,
            0L
        )
        if (!doWeHaveAnyStatusNeededRefreshing(statusRefresh)) {
            return
        }
        var resetTime = false
        if (isPumpNotReachable) {
            aapsLogger.info(LTag.PUMP, "Pump unreachable.")
            medtronicUtil.sendNotification(MedtronicNotificationType.PumpUnreachable, rh, rxBus)
            return
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        if (hasTimeDateOrTimeZoneChanged) {
            checkTimeAndOptionallySetTime()
            // read time if changed, set new time
            hasTimeDateOrTimeZoneChanged = false
        }

        // execute
        val refreshTypesNeededToReschedule: MutableSet<MedLinkMedtronicStatusRefreshType?> =
            HashSet()
        // B12: one synchronized call (was isEmpty() + first + poll(): crash on an empty plan, a step taken twice)
        val oper = tempBasalMicrobolusOperations.takeDueStep(LocalDateTime.now().plus(Seconds.seconds(30)))
        if (oper != null) {
            aapsLogger.info(LTag.PUMP, "Next Command " + oper.releaseTime.toDateTime())
            aapsLogger.info(LTag.PUMP, oper.toString())
            when (oper.operationType) {
                TempBasalMicroBolusPair.OperationType.SUSPEND -> {
                    tempBasalMicrobolusOperations.setShouldBeSuspended(true)
                    if (!currentPumpStatus.isSuspended() && currentPumpStatus != PumpRunningState.Unknow) {
                        stopPump(object : Callback() {
                            override fun run() {
                                aapsLogger.info(LTag.PUMP,"stopping pump")
                                if (medLinkPumpStatus.pumpRunningState ===
                                    PumpRunningState.TempBasalSuspended
                                ) {
                                    tempBasalMicrobolusOperations.complete(oper)
                                }
                            }
                        })
                    } else {
                        tempBasalMicrobolusOperations.complete(oper)
                    }
                }

                TempBasalMicroBolusPair.OperationType.REACTIVATE -> {
                    tempBasalMicrobolusOperations.setShouldBeSuspended(false)
                    // B12: always through startPump(). It skips the START only when the pump answered Running
                    // after the last start/stop (T13). The app state alone can be old: the REACTIVATE was skipped.
                    startPump(object : Callback() {
                        override fun run() {
                            if (medLinkPumpStatus.pumpRunningState === PumpRunningState.Running) {
                                tempBasalMicrobolusOperations.complete(oper)
                            }
                        }
                    }, true)
                }

                TempBasalMicroBolusPair.OperationType.BOLUS -> runTbrBolusStep(oper)
            }
        }

        for ((key, value) in statusRefresh!!) {
            aapsLogger.debug(
                LTag.PUMP, "Next Command  ${
                    (value!! - System.currentTimeMillis())
                }"
            )
            aapsLogger.debug(LTag.PUMP, key!!.name)
            if (value -
                System.currentTimeMillis() <= 0
            ) {
                //                scheduleNextRefresh(refreshType.getKey());
                when (key) {
                    MedLinkMedtronicStatusRefreshType.PumpHistory -> {
                        // T23: no S when the last one (or a STOP / START answer) is fresh; never older than 10 min
                        val now = dateUtil.now()
                        val lastStatusAt = medLinkPumpStatus.lastConnection
                        val wait = MedLinkStatusReadRule.statusReadWaitMs(now, lastStatusAt, lastStartStopAnswerAt)
                        if (wait > 0L) {
                            aapsLogger.info(
                                LTag.PUMPBTCOMM,
                                "status read skipped: last S ${(now - lastStatusAt) / 1000}s ago, " +
                                    "last STOP/START ${if (lastStartStopAnswerAt > 0L) "${(now - lastStartStopAnswerAt) / 1000}s ago" else "none"}, next in ${wait / 1000}s"
                            )
                            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory, now + wait - lastStatusAt)
                        } else {
                            aapsLogger.debug(LTag.PUMPBTCOMM, "refreshing")
                            readPumpData(true)
                        }
                    }

                    MedLinkMedtronicStatusRefreshType.PumpTime -> {
                        checkTimeAndOptionallySetTime()
                        refreshTypesNeededToReschedule.add(key)
                        resetTime = true
                    }

                    else -> {

                    }
                }
            }
        }
        if (medLinkPumpStatus.readProfile > 0 && System.currentTimeMillis() - medLinkPumpStatus.readProfile > 240000)
            readPumpProfileIfAllowed(MedLinkProfileReadRule.Reason.UNKNOWN_STATUS_LINE)
        if (medLinkStatusRefreshMap.isEmpty() || System.currentTimeMillis() - lastTryToConnect >= 600000) {
            val additionalMillis = if (medLinkStatusRefreshMap.isEmpty()) {
                300000L
            } else 0L
            lastTryToConnect = System.currentTimeMillis()
            readPumpData(false)
            // scheduleNextRefresh()
            scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory, additionalMillis);
            aapsLogger.info(LTag.PUMPBTCOMM, "posthistory")

        }
        if (resetTime) setLastCommunicationToNow()
    }

    private fun getStartStopCallback(
        operation: TempBasalMicroBolusPair?, callback: Function1<PumpEnactResult, *>,
        function: ChangeStatusCallback, isConnect: Boolean,
    ): Function<Supplier<Stream<String>>, MedLinkStandardReturn<PumpDriverState>> {
        return function.andThen(Function { f: MedLinkStandardReturn<PumpDriverState> ->
            when (f.functionResult) {
                PumpDriverState.Busy -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "busy $operation")
                    callback.invoke(
                        pumpEnactResultProvider.get() //
                            .success(false) //
                            .enacted(false) //
                            .comment(rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error))
                    )
                }

                PumpDriverState.Connected -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "connected $operation")
                    callback.invoke(
                        pumpEnactResultProvider.get() //
                            .success(isConnect) //
                            .enacted(isConnect) //
                            .comment(rh.gs(app.aaps.core.ui.R.string.careportal_tempbasalend))
                    )
                    if (isConnect) {
                        lastStatus = PumpRunningState.Running
                        tempBasalMicrobolusOperations.operations.peek()
                    } else {
                        operation?.isCommandIssued = false
                    }
                }

                PumpDriverState.Suspended -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "suspended $operation")
                    callback.invoke(
                        pumpEnactResultProvider.get() //
                            .success(!isConnect) //
                            .enacted(!isConnect) //
                            .comment(rh.gs(app.aaps.core.ui.R.string.careportal_tempbasalstart))
                    )
                    if (!isConnect) {
                        if ((temporaryBasal?.duration ?: 0L) > 0L) { // B12: null-safe, the plan can be empty here
                            lastStatus = PumpRunningState.TempBasalSuspended
                        } else {
                            lastStatus = PumpRunningState.Suspended
                        }
                        tempBasalMicrobolusOperations.operations.peek()
                        //                        createTemporaryBasalData(oper.getDuration(),
                        //                                oper.getDose().setScale(2).doubleValue());
                    } else {
                        operation?.isCommandIssued = false
                    }
                }

                else -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "else $operation")
                    callback.invoke(
                        pumpEnactResultProvider.get() //
                            .success(!isConnect) //
                            .enacted(!isConnect) //
                            .comment(rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error))
                    )
                    if (!isConnect) {
                        if ((temporaryBasal?.duration ?: 0L) > 0L) { // B12: null-safe, the plan can be empty here
                            lastStatus = PumpRunningState.TempBasalSuspended
                        } else {
                            lastStatus = PumpRunningState.Suspended
                        }
                        tempBasalMicrobolusOperations.operations.peek()
                        //                        createTemporaryBasalData(oper.getDuration(),
                        //                                oper.getDose().setScale(2).doubleValue());
                    }
                    operation?.isCommandIssued = false

                }
            }
            f
        })
    }

    fun createTemporaryBasalData(duration: Int, dose: Double) {
        val type: PumpSync.TemporaryBasalType =
            if (dose == 0.0) PumpSync.TemporaryBasalType.PUMP_SUSPEND else {
                PumpSync.TemporaryBasalType.NORMAL
            }

        var dur = duration
        if (duration == 0) {
            dur = durationInMinutes ?: duration
        }
        val timestamp = dateUtil.now()
        val tempData = PumpDbEntryTBR(
            timestamp,
            timestamp, pumpType,
            serialNumber(), null,
            dose,
            false, dur, type
        )
        medLinkPumpStatus.runningTBR = tempData
        aapsLogger.info(LTag.DATABASE, "syncing temp basal data")
        aapsLogger.info(LTag.DATABASE, "syncing temp basal data " + duration)
        if (dur > 0) {
            val result = runBlocking { pumpSync.syncTemporaryBasalWithPumpId(
                timestamp,
                PumpRate(dose),
                T.mins(dur.toLong()).msecs(),
                false,
                tempData.tbrType,
                timestamp,
                pumpType,
                serialNumber()
            ) }
            medLinkPumpStatus.tempBasalDuration = duration
            medLinkPumpStatus.tempBasalAmount = dose
            pumpSyncStorage.addTemporaryBasalRateWithTempId(tempData, true, this)
            preferences.inc(MedtronicLongNonKey.TbrsSet)
        }
        finishAction("TBR")
        aapsLogger.info(LTag.EVENTS, "CreateTemporaryData")
    }

    private fun reactivatePump(
        oper: TempBasalMicroBolusPair,
        callback: Function1<PumpEnactResult, *>
    ) {
        aapsLogger.debug(LTag.APS, "reactivate pump")
        medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(
            MedLinkPumpMessage<PumpDriverState, Any>(
                buildChangeStatusFunction(
                    oper,
                    callback, ChangeStatusCallback.OperationType.START
                ), btSleepTime
            )
        )
    }

    private fun buildChangeStatusFunction(
        oper: TempBasalMicroBolusPair?,
        callback: Function1<PumpEnactResult, *>,
        operationType: ChangeStatusCallback.OperationType,
    ): MutableList<CommandStructure<PumpDriverState, BleCommand>> {
        aapsLogger.info(LTag.PUMPBTCOMM, "changeStatusFunction")
        oper?.let { aapsLogger.info(LTag.PUMPBTCOMM, it.toStringView()) }
        aapsLogger.info(LTag.PUMPBTCOMM, operationType.name)

        val function = ChangeStatusCallback(
            aapsLogger,
            operationType, this
        )
        val commandPriority: CommandPriority
        val command: BleCommand
        val commandType: MedLinkCommandType
        if (operationType == ChangeStatusCallback.OperationType.START) {
            commandType = MedLinkCommandType.StartPump
            command = BleStartCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData, this)
            commandPriority = CommandPriority.HIGH
        } else {
            commandType = MedLinkCommandType.StopPump
            command = BleStopCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData, this)
            commandPriority = CommandPriority.LOWER
        }

        val startStopFunction =
            getStartStopCallback(oper, callback, function, true)
        return mutableListOf(
            CommandStructure(
                MedLinkCommandType.StopStartPump, Optional.of(startStopFunction),
                Optional.of(command), commandPriority = commandPriority
            ), CommandStructure(
                commandType, Optional.of(startStopFunction),
                Optional.of(command), commandPriority = commandPriority
            )
        )

    }

    override suspend fun getPumpStatus(status: String) {
        when {
            status.lowercase(Locale.getDefault()) == "clicked refresh" -> {
                readPumpData(false, 0L)
            }

            firstRun -> {
                initializePump(!isRefresh)
            }

            else -> {
                refreshAnyStatusThatNeedsToBeRefreshed()
            }
        }
        rxBus.send(EventMedtronicPumpValuesChanged())
    }

    fun sendChargingEvent() {
        //        rxBus.send(new EventChargingState(false, medLinkPumpStatus.batteryVoltage));
    }

    fun sendPumpUpdateEvent() {
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
        rxBus.send(EventMedtronicPumpValuesChanged())
    }

    private fun initializePump(b: Boolean) {
        aapsLogger.info(LTag.PUMP, logPrefix + "initializePump - start")
        aapsLogger.info(LTag.PUMP, logPrefix + b)
        if (medLinkMedtronicService != null) {
            if (!b) {
                medLinkMedtronicService?.deviceCommunicationManager?.wakeUp(false)
            }
            medLinkMedtronicService?.deviceCommunicationManager?.setDoWakeUpBeforeCommand(false)
        } else {
            aapsLogger.info(LTag.PUMP, "nullmedlinkservice$medLinkMedtronicService")
        }
        setRefreshButtonEnabled(false)
        readPumpData(false, 0L)
    }

    @SuppressLint("ResourceType")
    override fun postInit() {
        if (isRefresh) {
            if (isPumpNotReachable) {
                aapsLogger.info(LTag.PUMP, logPrefix + "initializePump::Pump unreachable.")
                medtronicUtil.sendNotification(MedtronicNotificationType.PumpUnreachable, rh, rxBus)
                setRefreshButtonEnabled(true)
                return
            }
            medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        }

        // model (once)
        if (medtronicUtil.medtronicPumpModel == null) {
            val func =
                Function<Supplier<Stream<String>>, MedLinkStandardReturn<MedLinkMedtronicDeviceType>> { f: Supplier<Stream<String>> ->
                    val res = MedLinkModelParser.parse(f)
                    medtronicUtil.medtronicPumpModel = res?.functionResult
                    res
                }
            val message = MedLinkPumpMessage<MedLinkMedtronicDeviceType, Any>(
                MedLinkCommandType.Connect,
                func,
                btSleepTime,
                BleConnectCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData, this),
                CommandPriority.HIGH
            )
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(message)
        } else {
            if (medLinkPumpStatus.medtronicDeviceType != medtronicUtil.medtronicPumpModel) {
                //                getAapsLogger().warn(LTag.PUMP, getLogPrefix() + "Configured pump is not the same as one detected.");
                //                medtronicUtil.sendNotification(MedtronicNotificationType.PumpTypeNotSame, getRh(), getRxBus());
            }
        }
        pumpState = PumpDriverState.Connected

        // time (1h)
        //        checkTimeAndOptionallySetTime();

        //        readPumpHistory();

        // remaining insulin (>50 = 4h; 50-20 = 1h; 15m)
        //        medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.GetRemainingInsulin);
        //        scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.RemainingInsulin, 10);

        // remaining power (1h)
        //        medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.GetBatteryStatus);
        //        scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.BatteryStatus, 20);
        aapsLogger.info(LTag.PUMPBTCOMM, "postinit")
        scheduleNextRefresh()

        // configuration (once and then if history shows config changes)
        //        medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.getSettings(medtronicUtil.getMedtronicPumpModel()));

        // B6: read the pump profile at app start. postInit is also called again after a MedLink wake-up (pump not seen
        // for 5 min): then the rule reads only if no read succeeded yet, and never during a bolus or a TBR operation.
        readPumpProfileIfAllowed(MedLinkProfileReadRule.Reason.APP_START)


        if (preferences.get(
                MedLinkBooleanPreferenceKey.LastBgHistory
            )
        ) {
            readPumpBGHistory(true)
        }

        if (preferences.get(
                MedLinkBooleanPreferenceKey.PreviousBgHistory
            )
        ) {
            previousBGHistory()
        }
        if (preferences.get(
                MedLinkBooleanPreferenceKey.LastBolusHistory
            )
        ) {
            aapsLogger.info(LTag.PUMPBTCOMM, "read bolus history")
            readBolusHistory()
        }
        if (preferences.get(
                MedLinkBooleanPreferenceKey.PreviousBolusHistory
            )
        ) {
            aapsLogger.info(LTag.PUMPBTCOMM, "read previous bolus history")
            readBolusHistory(true)
        }
        val errorCount = medLinkMedtronicService?.medtronicUIComm?.invalidResponsesCount
        if (errorCount!! >= 5 && activePlugin != null) {
            aapsLogger.error("Number of error counts was 5 or more. Starting tunning.")
            setRefreshButtonEnabled(true)
            serviceTaskExecutor.startTask(MedLinkWakeAndTuneTask(activePlugin!!))
            return
        }
        setLastCommunicationToNow()
        setRefreshButtonEnabled(true)
        rxBus.send(EventMedtronicPumpValuesChanged())
        if (!isRefresh) {
            pumpState = PumpDriverState.Initialized
        }
        isInitialized = true
        pumpState = PumpDriverState.Initialized
        firstRun = false
        medLinkPumpStatus.batteryAge =
            runBlocking { pumpSync.lastTherapyEvent(TE.Type.PUMP_BATTERY_CHANGE) }.map { it.toLong() }.orElse(0L)
        aapsLogger.info(LTag.EVENTS, "pump initialized")
    }

    override fun setSuspended() {
        this.medLinkPumpStatus.pumpRunningState = PumpRunningState.Suspended
        medLinkPumpStatus.recordRunningState()
    }

    private fun readBolusHistory() {
        this.readBolusHistory(false)
    }

    private fun readBolusHistory(previous: Boolean) {
        medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(bolusHistoryMessage(previous))
    }

    private fun bolusHistoryMessage(previous: Boolean): MedLinkPumpMessage<Stream<JSONObject>, Any> {
        aapsLogger.info(LTag.PUMPBTCOMM, "get full bolus history")
        lastBolusHistoryRead = System.currentTimeMillis()
        val func = BolusHistoryCallback(aapsLogger, this)
        val command: MedLinkCommandType = if (previous) {
            MedLinkCommandType.PreviousBolusHistory
        } else {
            MedLinkCommandType.BolusHistory
        }
        return MedLinkPumpMessage(
            command,
            func,
            btSleepTime, BlePartialCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData),
            CommandPriority.NORMAL
        )
    }

    private fun previousBGHistory() {
        if (activePlugin != null && activePlugin!!.activeBgSource.isMedLink) {
            lastPreviousHistory = System.currentTimeMillis()
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(previousBGHistory(false))
        }
    }

    private fun previousBGHistory(isCalibration: Boolean): MedLinkPumpMessage<BgSync.BgHistory, Any> {
        aapsLogger.info(LTag.PUMPBTCOMM, "get bolus history")
        val func = BGHistoryCallback(this, aapsLogger, true, isCalibration)
        return MedLinkPumpMessage<BgSync.BgHistory, Any>(
            MedLinkCommandType.PreviousBGHistory,  //                MedLinkCommandType.PreviousIsigHistory,
            func,  //                isigFunc,
            btSleepTime,
            BlePartialCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData),
            CommandPriority.NORMAL
        )
    }

    private fun checkTimeAndOptionallySetTime() {
        aapsLogger.info(LTag.PUMP, "MedtronicPumpPlugin::checkTimeAndOptionallySetTime - Start")
        setRefreshButtonEnabled(false)
        if (isPumpNotReachable) {
            aapsLogger.info(
                LTag.PUMP,
                "MedtronicPumpPlugin::checkTimeAndOptionallySetTime - Pump Unreachable."
            )
            setRefreshButtonEnabled(true)
            return
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)

        //        medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.GetRealTimeClock);
        val clock: ClockDTO = medtronicUtil.pumpTime ?: return

        //        if (clock == null) { // retry
        //            medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.GetRealTimeClock);
        //
        //            clock = medtronicUtil.getPumpTime();
        //        }

        val timeDiff = Math.abs(clock.timeDifference)
        if (timeDiff > 20) {
            if (clock.localDeviceTime.year <= 2015 || timeDiff <= 24 * 60 * 60) {
                aapsLogger.info(
                    LTag.PUMP,
                    "MedtronicPumpPlugin::checkTimeAndOptionallySetTime - Time difference is {} s. Set time on pump.",
                    timeDiff
                )

                //                medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.SetRealTimeClock);
                if (clock.timeDifference == 0) {
                    notificationManager.post(NotificationId.INSIGHT_DATE_TIME_UPDATED, rh.gs(app.aaps.core.ui.R.string.pump_time_updated), validMinutes = 60)
                }
            } else {
                if (clock.localDeviceTime.year > 2015) {
                    aapsLogger.error(
                        "MedtronicPumpPlugin::checkTimeAndOptionallySetTime - Time difference over 24h requested [diff={} s]. Doing nothing.",
                        timeDiff
                    )
                    medtronicUtil.sendNotification(
                        MedtronicNotificationType.TimeChangeOver24h,
                        rh,
                        rxBus
                    )
                }
            }
        } else {
            aapsLogger.info(
                LTag.PUMP,
                "MedtronicPumpPlugin::checkTimeAndOptionallySetTime - Time difference is {} s. Do nothing.",
                timeDiff
            )
        }
        scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpTime, 0)
    }

    /**
     * Saves the "insulin today" and "insulin yesterday" values from the status (S) answer
     * as pump TDD rows. The Statistics TDD table shows them next to the AAPS calculation.
     * The pump gives only the total (no basal/bolus split).
     * The day comes from the pump clock, because the pump counts its own day.
     * Rows are matched by day start, so each read updates the same two rows.
     */
    fun saveDailyTotals() {
        val today = medLinkPumpStatus.dailyTotalUnits
        val yesterday = medLinkPumpStatus.yesterdayTotalUnits
        if (today == null && yesterday == null) return
        val pumpTime = medLinkPumpStatus.lastDataTime.takeIf { it > 0L } ?: dateUtil.now()
        val serial = medLinkPumpStatus.serialNumber
        medLinkScope.launch {
            try {
                today?.let { saveDailyTotal(MidnightTime.calc(pumpTime), it, serial) }
                yesterday?.let { saveDailyTotal(MidnightTime.calcDaysBack(pumpTime, 1), it, serial) }
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "Could not save pump daily totals", e)
            }
        }
    }

    private suspend fun saveDailyTotal(dayStart: Long, total: Double, serial: String) {
        pumpSync.createOrUpdateTotalDailyDose(
            timestamp = dayStart,
            bolusAmount = 0.0,
            basalAmount = 0.0,
            totalAmount = total,
            pumpId = null,
            pumpType = pumpType,
            pumpSerial = serial
        )
        aapsLogger.debug(LTag.PUMP, "Pump daily total saved: ${dateUtil.dateString(dayStart)} $total U")
    }

    fun setPumpTime(currentTime: Long) {
        val clockDTO = ClockDTO(LocalDateTime(), LocalDateTime(currentTime))
        clockDTO.timeDifference = clockDTO.localDeviceTime.compareTo(clockDTO.pumpTime)
        medtronicUtil.pumpTime = clockDTO
    }

    private fun readPumpProfile() {
        medLinkPumpStatus.readProfile = 0
        lastProfileRead = System.currentTimeMillis()
        aapsLogger.info(LTag.PUMPBTCOMM, "get basal profiles")
        val basalCallback: Function<Supplier<Stream<String>>,
                MedLinkStandardReturn<BasalProfile>> = BasalCallback(aapsLogger, this)
        val profileCallback: Function<Supplier<Stream<String>>,
                MedLinkStandardReturn<Profile?>> = ProfileCallback(context, this, parser)
        val msg = BasalMedLinkMessage<BasalProfile>(
            basalCallback, profileCallback,
            btSleepTime, BleCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData)
        )
        // val profileMsg: MedLinkPumpMessage<Profile> = MedLinkPumpMessage(
        //     MedLinkCommandType.BaseProfile, profileCallback,
        //     btSleepTime, BleCommand(aapsLogger, medLinkmedLinkMedtronicService?.medLinkServiceData)
        // )

        medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)

        //        if (medtronicUITask.getResponseType() == MedtronicUIResponseType.Error) {
        //            getAapsLogger().info(LTag.PUMP, "reprocessing due to error response type");
        //            medLinkService.getMedtronicUIComm().executeCommandCP(msg);
        //        }
    }

    private fun scheduleNextRefresh() {
        scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory, 0)
    }

    private fun scheduleNextRefresh(
        refreshType: MedLinkMedtronicStatusRefreshType,
        additionalMillis: Long
    ): Int {
        when (refreshType) {
            MedLinkMedtronicStatusRefreshType.PumpHistory -> {
                aapsLogger.info(
                    LTag.PUMPBTCOMM, "Next refresh will be in " +
                            additionalMillis
                )


                workWithStatusRefresh(
                    StatusRefreshAction.Add, refreshType,
                    additionalMillis
                )
            }

            else -> {
                workWithStatusRefresh(
                    StatusRefreshAction.GetData, refreshType,
                    additionalMillis
                )
            }
        }
        return 0
    }


    var hasStarted = false

    @Synchronized
    fun workWithStatusRefresh(
        action: StatusRefreshAction?,  //
        statusRefreshType: MedLinkMedtronicStatusRefreshType?,  //
        time: Long,
    ): Map<MedLinkMedtronicStatusRefreshType?, Long?>? {
        return when (action) {
            StatusRefreshAction.Add -> {
                aapsLogger.info(
                    LTag.PUMPBTCOMM,
                    DateTime(System.currentTimeMillis() + time).toString()
                )
                //                if(!medLinkStatusRefreshMap.containsKey(statusRefreshType)) {
                medLinkStatusRefreshMap[statusRefreshType!!] =
                    medLinkPumpStatus.lastConnection + time
                //                }
                null
            }

            StatusRefreshAction.GetData -> {
                HashMap(medLinkStatusRefreshMap)
            }

            else -> null
        }
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean = isMedLinkProfileSet(profile)

    /** The Veo profile check, for any Profile (the MedLink profile parser returns a plain Profile). */
    fun isMedLinkProfileSet(profile: Profile): Boolean {
        aapsLogger.debug(
            LTag.PUMP,
            "isThisProfileSet: basalInitalized=" + medLinkPumpStatus.basalProfileStatus
        )
        // 4v: MedLink cannot write the basal profile to the Veo, so the profile is set in AAPS only; answering "not set"
        // made the queue ask again forever. B6: the pump profile is compared after each pump profile read, with the
        // ACTIVE AAPS profile (onPumpProfileRead), not here with the requested one (that message came before the switch).
        return true
    }

    /**
     * Last profile read from the pump as an AAPS profile (basal + carb ratios + ISF + BG targets), for "Update AAPS".
     * Null = not read yet, or AAPS cannot hold it (times not on the hour: AAPS profiles use whole hours only).
     */
    @Volatile var lastPumpProfile: Profile? = null
        private set

    /** When the last pump profile answer was read and parsed (also when AAPS cannot hold it): the 6 h read counts from here. */
    @Volatile var lastPumpProfileReadAt: Long = 0L
        private set

    /** The pump values per 30 min of the last read, exactly as on the pump (also times not on the hour). */
    @Volatile private var lastPumpValues: Map<MedLinkPumpProfileDiff.Kind, List<Double>>? = null

    /** Times of the last pump profile that are not on the hour ("12:30"): AAPS cannot take such a profile. */
    @Volatile private var lastPumpOffHourTimes: List<String> = emptyList()

    private val pumpBasalRound: (Double) -> Double = { rate -> pumpDescription.pumpType.determineCorrectBasalSize(rate) }

    /**
     * Called after the profile commands (E + F) were parsed (B6). Keeps the pump values and compares them with the
     * active AAPS profile: always pump -> AAPS, never AAPS -> pump (the Veo cannot be written). The reads come at app
     * start, every N hours (setting `ProfileReadIntervalHours`, T19), on the Profiles screen and when the basal in `S` differs.
     *
     * @param json the profile as parsed from the pump answer (keeps half hour times)
     * @param pumpProfile the same as an AAPS profile; null when AAPS cannot hold it (times not on the hour)
     */
    fun onPumpProfileRead(json: JSONObject, pumpProfile: Profile?) {
        // a parsed answer is a finished read, also when AAPS cannot hold it: no new read every 10 min (field log 2026-11-15)
        lastPumpProfileReadAt = System.currentTimeMillis()
        val values = MedLinkPumpProfileDiff.values(json, pumpBasalRound) ?: run {
            aapsLogger.warn(LTag.PUMP, "MedLink: pump profile read, but a list (basal, carb ratio, ISF, targets) is missing: not compared")
            return
        }
        lastPumpValues = values
        lastPumpProfile = pumpProfile
        lastPumpOffHourTimes = MedLinkPumpProfileDiff.offHourTimes(json)
        if (pumpProfile == null)
            aapsLogger.warn(LTag.PUMP, "MedLink: the pump profile has times not on the hour $lastPumpOffHourTimes: AAPS profiles use whole hours, it can only be compared")
        launchPumpProfileCheck(fromButton = false)
    }

    /** Button "Update AAPS" of the "profile is different" message: the one confirmation (user, 2026-11-15). */
    private fun reviewPumpProfile() {
        if (lastPumpProfile == null) return
        launchPumpProfileCheck(fromButton = true)
    }

    private fun launchPumpProfileCheck(fromButton: Boolean) {
        medLinkScope.launch {
            try {
                checkPumpProfile(fromButton)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "MedLink: pump profile check failed", e)
            }
        }
    }

    /**
     * B6: compares the pump values with the ACTIVE AAPS profile, with ALL values (basal, carb ratio, ISF, targets), every
     * 30 min, so a pump change at 12:30 is seen exactly.
     * Different: ONE question (user, 2026-11-15): the notification with all differences and "Update AAPS" / "Not now". It
     * stays until answered, so it is not lost. "Update AAPS" takes the pump profile at once (no second dialog).
     * When AAPS cannot hold the pump profile (times not on the hour): the differences and the times, and the user is asked
     * to move them on the pump to whole hours; only "Not now" (user, 2026-11-15).
     * The same: the notification goes away. A pump profile the user said "not now" to is not asked again until it changes.
     * @param fromButton true from "Update AAPS": compare again with the profile active now, then take the pump profile
     */
    private suspend fun checkPumpProfile(fromButton: Boolean) {
        val pumpValues = lastPumpValues ?: return
        val pumpProfile = lastPumpProfile
        val aapsProfile = profileFunction.get().getProfile() ?: run {
            aapsLogger.info(LTag.PUMP, "MedLink: pump profile read, but no active AAPS profile to compare")
            return
        }
        val differences = MedLinkPumpProfileDiff.differences(pumpValues, MedLinkPumpProfileDiff.values(aapsProfile, pumpBasalRound))
        if (differences.isEmpty()) {
            aapsLogger.info(LTag.PUMP, "MedLink: the pump profile is the same as the active AAPS profile ${profileFunction.get().getProfileName()}")
            notificationManager.dismiss(NotificationId.MEDLINK_PUMP_PROFILE_DIFFERS)
            return
        }
        if (fromButton) {
            // the user confirmed in the notification, which showed these differences
            if (pumpProfile == null) return
            aapsLogger.info(LTag.PUMP, "MedLink: user pressed Update AAPS, taking the pump profile: $differences")
            acceptPumpProfile(pumpProfile, aapsProfile)
            return
        }
        val hash = pumpValues.hashCode().toLong()
        if (hash == preferences.get(MedtronicLongNonKey.MedLinkIgnoredPumpProfile)) {
            aapsLogger.info(LTag.PUMP, "MedLink: the pump profile is different, but the user said not now to this pump profile: $differences")
            return
        }
        val lines = differences.joinToString("\n") { pumpProfileDiffLine(it) }
        if (pumpProfile == null) {
            val times = lastPumpOffHourTimes.joinToString(", ")
            aapsLogger.warn(LTag.PUMP, "MedLink: the pump profile is different from the active AAPS profile and has times not on the hour ($times): $differences")
            notificationManager.post(
                NotificationId.MEDLINK_PUMP_PROFILE_DIFFERS, rh.gs(R.string.medlink_pump_profile_not_on_hour, lines, times),
                actions = listOf(NotificationAction(R.string.medlink_not_now) { ignorePumpProfile(hash) })
            )
            return
        }
        aapsLogger.warn(LTag.PUMP, "MedLink: the pump profile is different from the active AAPS profile, asking the user: $differences")
        notificationManager.post(
            NotificationId.MEDLINK_PUMP_PROFILE_DIFFERS, rh.gs(R.string.medlink_pump_profile_differs_update, lines),
            actions = listOf(
                NotificationAction(R.string.medlink_update_aaps) { reviewPumpProfile() },
                NotificationAction(R.string.medlink_not_now) { ignorePumpProfile(hash) }
            )
        )
    }

    /** "Not now" / Cancel: keep AAPS as it is, and do not ask again until the pump profile changes. */
    private fun ignorePumpProfile(hash: Long) {
        preferences.put(MedtronicLongNonKey.MedLinkIgnoredPumpProfile, hash)
        notificationManager.dismiss(NotificationId.MEDLINK_PUMP_PROFILE_DIFFERS)
        aapsLogger.info(LTag.PUMP, "MedLink: pump profile not taken by the user (not asked again until it changes)")
    }

    private fun pumpProfileDiffLine(range: MedLinkPumpProfileDiff.Range): String = rh.gs(
        when (range.kind) {
            MedLinkPumpProfileDiff.Kind.BASAL       -> R.string.medlink_pump_profile_diff_basal
            MedLinkPumpProfileDiff.Kind.IC          -> R.string.medlink_pump_profile_diff_ic
            MedLinkPumpProfileDiff.Kind.ISF         -> R.string.medlink_pump_profile_diff_isf
            MedLinkPumpProfileDiff.Kind.TARGET_LOW  -> R.string.medlink_pump_profile_diff_target_low
            MedLinkPumpProfileDiff.Kind.TARGET_HIGH -> R.string.medlink_pump_profile_diff_target_high
        },
        MedLinkPumpProfileDiff.time(range.fromSlot), MedLinkPumpProfileDiff.time(range.toSlot), range.pump, range.aaps
    )

    /** Saves the pump profile as a new local profile and switches to it (100 %, no time shift). DIA/insulin: from AAPS. */
    private suspend fun acceptPumpProfile(pumpProfile: Profile, aapsProfile: EffectiveProfile) {
        val now = dateUtil.now()
        val repository = profileRepository.get()
        val single = repository.copyFrom(pumpProfile.convertToNonCustomizedProfile(dateUtil), rh.gs(R.string.medlink_pump_profile_name, dateUtil.dateAndTimeString(now)))
        repository.add(single).onFailure {
            aapsLogger.error(LTag.PUMP, "MedLink: could not save the pump profile", it)
            notificationManager.post(NotificationId.FAILED_UPDATE_PROFILE, rh.gs(R.string.medlink_pump_profile_switch_failed, single.name), level = NotificationLevel.NORMAL)
            return
        }
        val store = repository.profile.value
        val result = store?.let {
            profileFunction.get().createProfileSwitch(
                profileStore = it,
                profileName = single.name,
                durationInMinutes = 0,
                percentage = 100,
                timeShiftInHours = 0,
                timestamp = now,
                action = Action.PROFILE_SWITCH,
                source = Sources.Medtronic,
                note = rh.gs(R.string.medlink_pump_profile_changed_title),
                listValues = listOf(ValueWithUnit.SimpleString(single.name)),
                iCfg = aapsProfile.iCfg
            )
        }
        if (result == null) {
            aapsLogger.error(LTag.PUMP, "MedLink: profile switch to ${single.name} failed")
            notificationManager.post(NotificationId.FAILED_UPDATE_PROFILE, rh.gs(R.string.medlink_pump_profile_switch_failed, single.name), level = NotificationLevel.NORMAL)
        } else {
            aapsLogger.info(LTag.PUMP, "MedLink: pump profile accepted, switched to ${single.name}")
            notificationManager.dismiss(NotificationId.MEDLINK_PUMP_PROFILE_DIFFERS)
        }
    }

    private fun isProfileSame(profile: Profile): Boolean {
        var invalid = false
        val basalsByHour = medLinkPumpStatus.basalsByHour
        aapsLogger.debug(
            LTag.PUMP, "Current Basals (h):   "
                    + if (basalsByHour == null) "null" else basalProfile!!.basalProfileAsString
        )

        // int index = 0;
        if (basalsByHour == null) return true // we don't want to set profile again, unless we are sure
        val stringBuilder = StringBuilder("Requested Basals (h): ")
        for (basalValue in profile.getBasalValues()) {
            val basalValueValue =
                pumpDescription.pumpType.determineCorrectBasalSize(basalValue.value)
            val hour = basalValue.timeAsSeconds / (60 * 60)
            if (!MedLinkMedtronicUtil.isSame(basalsByHour[hour], basalValueValue)) {
                invalid = true
            }
            stringBuilder.append(String.format(Locale.ENGLISH, "%.3f", basalValueValue))
            stringBuilder.append(" ")
        }
        aapsLogger.debug(LTag.PUMP, stringBuilder.toString())
        if (!invalid) {
            aapsLogger.debug(LTag.PUMP, "Basal profile is same as AAPS one.")
        } else {
            aapsLogger.debug(LTag.PUMP, "Basal profile on Pump is different than the AAPS one.")
        }
        return !invalid
    }

//    override fun lastDataTime(): Long {
//        return if (medLinkPumpStatus.lastConnection != 0L) {
//            medLinkPumpStatus.lastConnection
//        } else System.currentTimeMillis()
//    }

    /** Pump basal of the current hour (U/h) for the MedLink TBR math. */
    val medLinkBaseBasalRate: Double
        get() = medLinkPumpStatus.basalProfileForHour

    override val baseBasalRate: PumpRate
        get() = PumpRate(medLinkBaseBasalRate)

    /** B8: Stop was pressed for the manual bolus in flight: its result is told by a notification (the dialog is closed). */
    @Volatile private var manualBolusStopRequested = false

    override fun stopBolusDelivering() {
        bolusDeliveryType = BolusDeliveryType.CancelDelivery
        // Not written yet (still QUEUED): the flag above and the queue removal are enough
        val request = medLinkMedtronicService?.medLinkBLE?.bolusTracker?.current ?: return
        if (request.state.isFinal || request.state == MedLinkBolusState.QUEUED) return
        // The bolus is being delivered: only a STOP of the pump ends it
        val minimum = preferences.get(MedLinkMedtronicIntPreferenceKey.MinBatteryForSuspend)
        val low = lowBatteryForSuspend(minimum)
        if (low != null) {
            // Same rule as every suspension: nobody could start the pump again. The bolus goes on.
            aapsLogger.warn(LTag.PUMP, "bolus stop: pump not stopped, ${low.device} battery ${low.level}% (minimum $minimum%)")
            // B8: the dialog says so; the next progress update shows the bolus again (it goes on)
            bolusProgressData.state.value?.let { bolusProgressData.updateProgress(it.percent, rh.gs(R.string.medlink_bolus_not_stopped), it.delivered) }
            uiInteraction.runAlarm(
                rh.gs(R.string.medlink_bolus_stop_low_battery, batteryDeviceName(low), low.level, minimum),
                rh.gs(R.string.medlink_bolus_not_stopped),
                app.aaps.core.ui.R.raw.boluserror
            )
            return
        }
        aapsLogger.info(LTag.PUMP, "bolus stop: bolus ${request.state}, stopping the pump")
        // B8 (user 2026-11-14): the dialog closes at once; a notification tells the delivered amount when the pump reports it
        manualBolusStopRequested = true
        bolusProgressData.clear(bolusProgressData.currentGeneration)
        // USER stop: sent at once, aborts the bolus. The tracker then reads M and records the partial amount.
        stopPump(object : Callback() {
            override fun run() {
                if (!result.success) {
                    aapsLogger.warn(LTag.PUMP, "bolus stop: pump not suspended (${result.comment})")
                    return
                }
                if (tempBasalMicrobolusOperations.shouldBeSuspended()) {
                    // TBR off-time (suspension of a TBR below 100 %, or 0 U/h): the plan starts the pump later
                    aapsLogger.info(LTag.PUMP, "bolus stop: pump kept stopped, the temp basal wants it suspended now")
                } else {
                    aapsLogger.info(LTag.PUMP, "bolus stop: starting the pump again")
                    startPump(null)
                }
            }
        }, MedLinkStopOrigin.USER)
    }

    private fun setRefreshButtonEnabled(enabled: Boolean) {
        rxBus.send(EventRefreshButtonState(enabled))
    }//

    //        return (!rileyLinkMedtronicService.getDeviceCommunicationManager().isDeviceReachable());
    //
    private val isPumpNotReachable: Boolean
        private get() {
            val medLinkServiceState = medLinkServiceData.medLinkServiceState

            if (medLinkServiceState != MedLinkServiceState.PumpConnectorReady //
                && medLinkServiceState != MedLinkServiceState.MedLinkReady //
                && medLinkServiceState != MedLinkServiceState.TuneUpDevice
            ) {
                aapsLogger.info(LTag.PUMP, "RileyLink unreachable.")
                return false
            }
            return false
            //        return (!rileyLinkMedtronicService.getDeviceCommunicationManager().isDeviceReachable());
        }

    private fun readTBR(): TempBasalPair {
        return TempBasalPair(
            medLinkPumpStatus.tempBasalAmount ?: 0.0, true,
            medLinkPumpStatus.tempBasalDuration ?: 0
        )
        //        MedtronicUITask responseTask = null;//rileyLinkMedtronicService.getMedtronicUIComm().executeCommand(MedtronicCommandType.ReadTemporaryBasal);
        //
        //        if (responseTask.hasData()) {
        //            TempBasalPair tbr = (TempBasalPair) responseTask.returnData;
        //
        //            // we sometimes get rate returned even if TBR is no longer running
        //            if (tbr.getDurationMinutes() == 0) {
        //                tbr.setInsulinRate(0.0d);
        //            }
        //
        //            return tbr;
        //        } else {
        //            return null;
        //        }
    }

    private fun finishAction(overviewKey: String?) {
        if (overviewKey != null) rxBus.send(EventRefreshOverview(overviewKey, false))
        triggerUIChange()
        setRefreshButtonEnabled(true)
    }

    private fun incrementStatistics(statsKey: String) {
//        var currentCount = sp?.getLong(statsKey, 0L) ?: 0L
//        currentCount++
//        sp?.putLong(statsKey, currentCount)
    }

    // if enforceNew===true current temp basal is canceled and new TBR set (duration is prolonged),
    // if false and the same rate is requested enacted=false and success=true is returned and TBR is not changed
    override fun setTempBasalAbsolute(
        requestedRate: Double, durationInMinutes: Int, profile: Profile,
        enforceNew: Boolean, callback: Function1<PumpEnactResult, *>,
    ) {
        val absoluteRate = postBolusMinBasal(requestedRate, profile)
        // kept before the old plan is cleared: a new TBR must not move the first operation later
        val previousFirst = tempBasalMicrobolusOperations.operations.peekFirst()
        val result: PumpEnactResult
        medLinkPumpStatus.tempBasalAmount = absoluteRate
        val forceStop = System.currentTimeMillis() - lastSMBBolus < 80000
        // checkPumpNeedToBeStarted(absoluteRate, profile)
        aapsLogger.info(LTag.PUMPBTCOMM, "absolute rate $absoluteRate $durationInMinutes")
        aapsLogger.info(LTag.PUMPBTCOMM, "gettempBasal $temporaryBasal")
        result = if (absoluteRate != 0.0 && (absoluteRate == medLinkBaseBasalRate ||
                    Math.abs(absoluteRate - medLinkBaseBasalRate) < pumpDescription.bolusStep)
        ) {
            aapsLogger.info(LTag.EVENTS, "cancelling temp basal")
            aapsLogger.info(LTag.EVENTS, "" + medLinkBaseBasalRate)
            aapsLogger.info(LTag.EVENTS, "" + absoluteRate)
            aapsLogger.info(LTag.EVENTS, "" + pumpDescription.bolusStep)
            clearTempBasal()
            pumpEnactResultProvider.get().enacted(true).success(true)
        } else if (absoluteRate == 0.0 && (temporaryBasal != null && temporaryBasal!!.desiredRate == absoluteRate && absoluteRate == medLinkPumpStatus.currentBasal) || absoluteRate == medLinkPumpStatus.currentBasal) {
            aapsLogger.info(LTag.EVENTS, "extendbasaltreatment")
            if ((medLinkPumpStatus.pumpRunningState === PumpRunningState.Running || medLinkPumpStatus.pumpRunningState === PumpRunningState.Unknow || forceStop) && !skipSafetyStop()) {
                stopPump(object : Callback() {
                    override fun run() {
                        aapsLogger.info(LTag.PUMP, "Stopping unstopped pump")
                    }
                })
            }
            extendBasalTreatment(durationInMinutes, callback)
        } else {
            if (absoluteRate == medLinkBaseBasalRate
            // ||(absoluteRate< medLinkPumpStatus.currentBasal && Math.abs(absoluteRate - medLinkPumpStatus.currentBasal) < pumpDescription.bolusStep)
            ) {
                aapsLogger.info(LTag.EVENTS, "clearing temp basal")
                aapsLogger.info(LTag.EVENTS, "" + medLinkBaseBasalRate)
                aapsLogger.info(LTag.EVENTS, "" + absoluteRate)
                aapsLogger.info(LTag.EVENTS, "" + pumpDescription.bolusStep)
                clearTempBasal()
            } else if (keepTbrPlanIfSame(MedLinkTbrKeepRule.percentOf(absoluteRate, profile.getBasal()), durationInMinutes, profile, callback)) {
                buildPumpEnactResult().enacted(true).success(true).comment(rh.gs(R.string.medtronic_cmd_desc_set_tbr))
            } else if (absoluteRate < medLinkBaseBasalRate) {
                aapsLogger.info(LTag.EVENTS, "suspending")
                aapsLogger.info(LTag.EVENTS, "" + medLinkBaseBasalRate)
                aapsLogger.info(LTag.EVENTS, "" + absoluteRate)
                aapsLogger.info(LTag.EVENTS, "" + pumpDescription.bolusStep)
                tempBasalMicrobolusOperations.clearOperations()
                val result = scheduleSuspension(
                    0, durationInMinutes, profile, callback,
                    absoluteRate,
                    PumpTempBasalType.Absolute,
                    previousFirst
                ) ?: buildPumpEnactResult().success(true).enacted(false) // 4ae: was !!, null before the first start
                if(tempBasalMicrobolusOperations.operations.isEmpty()){
                    clearTempBasal()
                }
                // Plan carry fix (field 2026-11-16 02:31:44): no "extend" of the old plan here any more. The new plan already
                // has its REACTIVATE at the right time (built from now for the whole TBR). The old block set that REACTIVATE
                // to now + (new - old REACTIVATE) minutes, about now: STOP and START 4 s apart.
                result
            } else {
                aapsLogger.info(LTag.EVENTS, "bolusingbasal")
                aapsLogger.info(LTag.EVENTS, "" + medLinkBaseBasalRate)
                aapsLogger.info(LTag.EVENTS, "" + absoluteRate)
                aapsLogger.info(LTag.EVENTS, "" + pumpDescription.bolusStep)
                clearTempBasal()
                scheduleTempBasalBolus(
                    0, durationInMinutes, profile, callback,
                    absoluteRate, PumpTempBasalType.Absolute,
                    previousFirst
                )
            }
            //        result.success = false;
            //        result.comment = MainApp.gs(R.string.pumperror);
        }

        aapsLogger.info(LTag.EVENTS, "Settings temp basal percent: $result")
        // The queue waits for this answer (AAPS 4.0). The plan runs on its own, so answer now: without it the
        // queue was blocked 10 min after every TBR and a manual bolus waited behind it. Only the first answer counts.
        callback(result)
    }

    fun setTempBasalAbsolute(
        absoluteRate: Double, durationInMinutes: Int, profile: Profile,
        enforceNew: Boolean,
    ): PumpEnactResult {
        checkPumpNeedToBeStarted(absoluteRate, profile)
        setRefreshButtonEnabled(false)
        if (isPumpNotReachable) {
            setRefreshButtonEnabled(true)
            aapsLogger.info(LTag.PUMP, "pump unreachable")
            return pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(false) //
                .comment(rh.gs(app.aaps.core.ui.R.string.pump_unreachable))
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        aapsLogger.info(
            LTag.PUMP,
            logPrefix + "setTempBasalAbsolute: rate: " + absoluteRate + ", duration=" + durationInMinutes
        )

        // read current TBR
        val tbrCurrent = readTBR()
        aapsLogger.info(
            LTag.PUMP,
            logPrefix + "setTempBasalAbsolute: Current Basal: duration: " + tbrCurrent.durationMinutes + " min, rate=" + tbrCurrent.insulinRate
        )
        if (!enforceNew) {
            if (MedLinkMedtronicUtil.isSame(tbrCurrent.insulinRate, absoluteRate)) {
                var sameRate = true
                if (MedLinkMedtronicUtil.isSame(0.0, absoluteRate) && durationInMinutes > 0) {
                    // if rate is 0.0 and duration>0 then the rate is not the same
                    sameRate = false
                }
                if (sameRate) {
                    aapsLogger.info(
                        LTag.PUMP,
                        logPrefix + "setTempBasalAbsolute - No enforceNew and same rate. Exiting."
                    )
                    finishAction("TBR")
                    return pumpEnactResultProvider.get().success(true).enacted(false)
                }
            }
            // if not the same rate, we cancel and start new
        }

        // if TBR is running we will cancel it.
        if (tbrCurrent.insulinRate != 0.0 && tbrCurrent.durationMinutes > 0) {
            aapsLogger.info(
                LTag.PUMP,
                logPrefix + "setTempBasalAbsolute - TBR running - so canceling it."
            )

            // CANCEL
            cancelTempBasal(true, object : Callback() {
                override fun run() {
                    aapsLogger.info(LTag.PUMPBTCOMM, "tbr cancelled")
                }
            })
            Toast.makeText(
                context, app.aaps.core.ui.R.string.temp_basal_delivery_error, Toast.LENGTH_SHORT
            ).show()
            //            Boolean response = (Boolean) responseTask2.returnData;

            //            if (response) {
            //                getAapsLogger().info(LTag.PUMP, getLogPrefix() + "setTempBasalAbsolute - Current TBR cancelled.");
            //            } else {
            //                getAapsLogger().error(getLogPrefix() + "setTempBasalAbsolute - Cancel TBR failed.");
            //
            //                finishAction("TBR");
            return pumpEnactResultProvider.get().success(false).enacted(false)
                .comment(rh.gs(R.string.medtronic_cmd_cant_cancel_tbr_stop_op))
            //            }
        }
        return pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.medtronic_cmd_cant_cancel_tbr_stop_op))
        // now start new TBR
        //        MedtronicUITask responseTask = null;//rileyLinkMedtronicService.getMedtronicUIComm().executeCommand(MedtronicCommandType.SetTemporaryBasal,
        //        //absoluteRate, durationInMinutes);
        //
        //        Boolean response = (Boolean) responseTask.returnData;
        //
        //        getAapsLogger().info(LTag.PUMP, getLogPrefix() + "setTempBasalAbsolute - setTBR. Response: " + response);
        //
        //        if (response) {
        //            // FIXME put this into UIPostProcessor
        //            medtronicPumpStatus.tempBasalStart = new Date();
        //            medtronicPumpStatus.tempBasalAmount = absoluteRate;
        //            medtronicPumpStatus.tempBasalLength = durationInMinutes;
        //
        //            TemporaryBasal tempStart = new TemporaryBasal(getInjector()) //
        //                    .date(System.currentTimeMillis()) //
        //                    .duration(durationInMinutes) //
        //                    .absolute(absoluteRate) //
        //                    .source(Source.USER);
        //
        //            activePlugin.getActiveTreatments().addToHistoryTempBasal(tempStart);
        //
        //            incrementStatistics(MedtronicConst.Statistics.TBRsSet);
        //
        //            finishAction("TBR");
        //
        //            return new PumpEnactResult(getInjector()).success(true).enacted(true) //
        //                    .absolute(absoluteRate).duration(durationInMinutes);
        //
        //        } else {
        //            finishAction("TBR");
        //
        //            return new PumpEnactResult(getInjector()).success(false).enacted(false) //
        //                    .comment(getRh().gs(R.string.medtronic_cmd_tbr_could_not_be_delivered));
        //        }
    }

    private fun checkPumpNeedToBeStarted(absoluteRate: Double, profile: Profile) {
        val previousBasal = temporaryBasal
        if (previousBasal != null) {
            if (absoluteRate > 0.0 && previousBasal.desiredRate!! < profile.getBasal() && absoluteRate > profile.getBasal()) {
                aapsLogger.info(LTag.CORE, "starting pump")
                startPump(object : Callback() {
                    override fun run() {}
                }, true)
            }
        }
    }

    protected fun clearTempBasal(): PumpEnactResult {
        aapsLogger.info(LTag.EVENTS, " clearing temp basal")
        val result = buildPumpEnactResult()
        result.success = true
        result.comment = rh.gs(app.aaps.core.ui.R.string.cancel_temp)
        // B17: also when the old plan had suspensions: its STOP can still be on its way (app state still Running)
        val oldPlanSuspends = tempBasalMicrobolusOperations.operations.any { it.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND }
        if (currentPumpStatus.isSuspended() || medLinkPumpStatus.pumpRunningState.isSuspended() || oldPlanSuspends) {
            runPumpForNewPlan("temp basal cleared")
        }
        tempBasalMicrobolusOperations.clearOperations()
        //        createTemporaryBasalData(0, 0);
        return result
    }

    /**
     * B17 (field log 2026-11-17 08:20): a new plan needs the pump running now, but a STOP of the old plan was still
     * on its way and stopped the pump 2 min longer than asked. Drops the old automatic STOPs still waiting in the
     * MedLink queue and sends START with high priority, so it runs right after a STOP being sent now.
     * startPump() skips the START when the pump answered Running after the last start / stop (T13).
     */
    private fun runPumpForNewPlan(reason: String) {
        aapsLogger.info(LTag.PUMP, "B17: the new plan needs the pump running ($reason)")
        medLinkMedtronicService?.medLinkBLE?.dropQueuedAutomaticStops(reason)
        stopQueuedAt = 0L // B18: the waiting STOPs are gone (a STOP being sent now still answers)
        startPump(object : Callback() {
            override fun run() {
                aapsLogger.info(LTag.PUMP, "B17: start for the new plan ($reason): ${result?.success}")
            }
        }, true)
    }

    /**
     * B14: the steps (SUSPEND / REACTIVATE) of a TBR below the basal, from [MedLinkSuspensionPlan]. The plugin only
     * turns them into plan operations and logs the plan.
     * @param now when the plan starts (now, or the old end of a plan that is extended, T24)
     */
    private fun buildSuspensionScheduler(
        totalSuspendedMinutes: Int,
        durationInMinutes: Int,
        callback: Function1<*, *>,
        now: Long = System.currentTimeMillis(),
    ): LinkedBlockingDeque<TempBasalMicroBolusPair> {
        val plan = MedLinkSuspensionPlan.build(totalSuspendedMinutes, durationInMinutes, LocalDateTime(now))
        aapsLogger.info(
            LTag.PUMP,
            "MedLink temp basal plan: suspend ${plan.suspendMinutes} min / active ${plan.activeMinutes} min, " +
                (if (plan.activeMinutesLeft > 0) "starts active for ${plan.activeMinutesLeft} min" else "starts suspended") +
                " ($totalSuspendedMinutes of $durationInMinutes min suspended, ${plan.steps.size / 2} suspensions)"
        )
        return plan.steps.mapTo(LinkedBlockingDeque()) {
            TempBasalMicroBolusPair(totalSuspendedMinutes, 0.0, 0.0, it.at, it.type, callback)
        }
    }

    private fun scheduleSuspension(
        percent: Int,
        durationInMinutes: Int,
        profile: Profile,
        callback: Function1<*, *>,
        absoluteBasalValue: Double,
        pumpTempBasalType: PumpTempBasalType,
        previousFirst: TempBasalMicroBolusPair?,
    ): PumpEnactResult? {
        this.durationInMinutes = durationInMinutes
        // B14: the minutes to suspend come from MedLinkSuspensionPlan (part of the basal the pump must give)
        val deliveredFraction = when {
            pumpTempBasalType != PumpTempBasalType.Absolute -> percent / 100.0
            absoluteBasalValue != 0.0                     -> absoluteBasalValue / profile.getBasal()
            percent == 0                                  -> 0.0
            else                                          -> 1.0
        }
        val totalSuspendedMinutes = MedLinkSuspensionPlan.suspendedMinutes(durationInMinutes, deliveredFraction)
        this.percent = percent
        if (totalSuspendedMinutes < MedLinkSuspensionPlan.MIN_SEGMENT_MINUTES) {
            return result
        } else {
            val operations = applyPlanCarry(
                buildSuspensionScheduler(totalSuspendedMinutes, durationInMinutes, callback),
                previousFirst, durationInMinutes
            )
            // 4x: the plan starts active when its first suspension is later (see MedLinkSuspendStartRule)
            val startActive = operations.firstOrNull()?.let {
                it.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND && it.releaseTime.isAfter(LocalDateTime.now().plusSeconds(30))
            } == true
            tempBasalMicrobolusOperations.updateOperations(
                operations.size, 0.0,
                operations,
                totalSuspendedMinutes
            )
            tempBasalMicrobolusOperations.absoluteRate = absoluteBasalValue
            tempBasalMicrobolusOperations.durationInMinutes = durationInMinutes
            markPlanMade(if (pumpTempBasalType == PumpTempBasalType.Absolute) MedLinkTbrKeepRule.percentOf(absoluteBasalValue, profile.getBasal()) else percent)
            tempBasalMicrobolusOperations.let {
                // B12: one read of the first step (another thread can take it)
                val first = it.firstOrNull()
                if (first != null &&
                    first.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND &&
                    currentPumpStatus.isSuspended() && first.releaseTime.isBefore(
                        LocalDateTime.now()
                    )
                ) {
                    it.complete(first)
                    stopPump(object : Callback() {
                        override fun run() {
                            aapsLogger.info(LTag.PUMP,"stopping pump")
                        }
                    })
                } else if (first != null &&
                    first.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND &&
                    (startActive || (percent > 0 && currentPumpStatus.isSuspended()))
                ) {
                    // B17: a plan that starts active needs the pump running now, also when the app state still says
                    // Running while a STOP of the old plan is on its way (startPump skips a pump confirmed running)
                    runPumpForNewPlan(if (startActive) "plan starts active" else "plan percent above 0")
                }
                it
            }
        }


        refreshAnyStatusThatNeedsToBeRefreshed()
        //criar fila de comandos aqui, esta fila deverá ser consumida a cada execução de checagem de status
        return buildPumpEnactResult().enacted(true).success(true)
            .comment(rh.gs(R.string.medtronic_cmd_desc_set_tbr))
    }

    /** T24: the TBR percent the running plan was made for (an absolute TBR as a percent of the basal). */
    @Volatile private var planPercent = 0

    /** T24: when the running plan was made or last extended. */
    @Volatile private var planMadeAt = 0L

    /** T24: the plan builders read the clock from [currentTime]; a plan extension sets it to the old end of the plan. */
    @Volatile private var planClock: LocalDateTime? = null

    private fun markPlanMade(percent: Int) {
        planPercent = percent
        planMadeAt = dateUtil.now()
    }

    /**
     * T24 (`MedLinkTbrKeepRule`): a new TBR with the same percent as the running plan keeps it (less than 15 min after
     * the plan was made), or keeps its steps and adds only the period from its old end to the new end (15 min or more).
     * @return true when the plan was kept or extended: the caller must not clear or rebuild it
     */
    private fun keepTbrPlanIfSame(newPercent: Int, durationInMinutes: Int, profile: Profile, callback: Function1<*, *>): Boolean {
        val plan = tempBasalMicrobolusOperations
        val now = dateUtil.now()
        val oldEnd = plan.endsAt
        val newEnd = now + durationInMinutes * 60_000L
        val decision = MedLinkTbrKeepRule.decide(now, plan.operations.isNotEmpty(), planPercent, newPercent, planMadeAt, oldEnd, newEnd)
        val ends = LocalDateTime(oldEnd).toString("HH:mm")
        when (decision.action) {
            MedLinkTbrKeepRule.Action.REBUILD -> {
                if (plan.operations.isNotEmpty()) aapsLogger.info(LTag.PUMP, "TBR $newPercent %: new plan (${decision.reason})")
                return false
            }

            MedLinkTbrKeepRule.Action.KEEP    -> {
                aapsLogger.info(LTag.PUMP, "TBR $newPercent %: plan kept as it is, ends $ends (${decision.reason})")
                return true
            }

            MedLinkTbrKeepRule.Action.EXTEND  -> {
                val added = extendPlan(oldEnd, newEnd, newPercent, profile, callback)
                plan.durationInMinutes = durationInMinutes // the plan now ends at the new end
                planMadeAt = now
                aapsLogger.info(
                    LTag.PUMP,
                    "TBR $newPercent %: plan kept, $added steps added from $ends to ${LocalDateTime(newEnd).toString("HH:mm")} (${decision.reason})"
                )
                return true
            }
        }
    }

    /**
     * T24: plans only the period from [oldEnd] to [newEnd] with the normal builders, as if it were [oldEnd] now, and adds
     * the steps to the running plan. Below 100 %: a period too short for one suspension is fully suspended (less
     * insulin, never more). Above 100 %: less than one bolus step in the period gives no step (never more insulin).
     * @return the number of steps added
     */
    private fun extendPlan(oldEnd: Long, newEnd: Long, percent: Int, profile: Profile, callback: Function1<*, *>): Int {
        val minutes = ((newEnd - oldEnd) / 60_000L).toInt()
        if (minutes < Constants.INTERVAL_BETWEEN_OPERATIONS) return 0
        val ops = tempBasalMicrobolusOperations.operations
        val tail: LinkedBlockingDeque<TempBasalMicroBolusPair>
        if (percent < 100) {
            var suspended = MedLinkSuspensionPlan.suspendedMinutes(minutes, percent / 100.0)
            if (suspended < Constants.INTERVAL_BETWEEN_OPERATIONS) suspended = minOf(minutes, Constants.INTERVAL_BETWEEN_OPERATIONS)
            tail = buildSuspensionScheduler(suspended, minutes, callback, oldEnd)
            val last = ops.peekLast()
            val first = tail.peekFirst()
            // the old plan starts the pump at its end and the new period stops it again at once: drop both commands
            if (last != null && first != null && !last.isCommandIssued &&
                last.operationType == TempBasalMicroBolusPair.OperationType.REACTIVATE &&
                first.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND &&
                abs(first.releaseTime.toDate().time - last.releaseTime.toDate().time) < 60_000L
            ) {
                ops.removeLast()
                tail.removeFirst()
            }
        } else {
            val start = LocalDateTime(oldEnd)
            planClock = start
            try {
                val startTas = start.millisOfDay / 1000
                val profiles = extractTempProfiles(profile, startTas + minutes * 60L, startTas)
                tail = buildFirstLevelTempBasalMicroBolusOperations(percent, profiles, minutes, callback, 0.0, PumpTempBasalType.Percent).operations
            } finally {
                planClock = null
            }
        }
        ops.addAll(tail)
        return tail.size
    }

    private fun buildFirstLevelTempBasalMicroBolusOperations(
        percent: Int, basalProfilesFromTemp: LinkedList<Profile.ProfileValue?>,
        durationInMinutes: Int, callback: Function1<*, *>, absoluteBasalValue: Double,
        basalType: PumpTempBasalType,
    ): TempBasalMicrobolusOperations {
        val insulinPeriod: MutableList<TempBasalMicroBolusDTO> = ArrayList()
        var previousProfileValue: Profile.ProfileValue? = null
        var spentBasalTimeInSeconds = 0
        var currentStep = 0
        var totalAmount = 0.0
        var durationInSeconds = durationInMinutes * 60
        val time = currentTime
        var startedTime = time.hourOfDay * 3600 + time.minuteOfHour * 60 + time.secondOfMinute
        while (!basalProfilesFromTemp.isEmpty()) {
            val currentProfileValue = basalProfilesFromTemp.pollFirst()
            if (previousProfileValue == null) {
                previousProfileValue = currentProfileValue
            }
            if (currentProfileValue!!.timeAsSeconds == 0) {
                if (currentStep > 0) {
                    startedTime = 0
                }
                durationInSeconds -= spentBasalTimeInSeconds
                spentBasalTimeInSeconds = 0
            }
            val tempBasalPair = calculateTempBasalDosage(
                startedTime + spentBasalTimeInSeconds,
                currentProfileValue, basalProfilesFromTemp, percent, absoluteBasalValue,
                durationInSeconds - spentBasalTimeInSeconds, basalType
            )
            totalAmount += tempBasalPair.insulinRate
            spentBasalTimeInSeconds += tempBasalPair.durationMinutes * 60
            insulinPeriod.add(tempBasalPair)
            currentStep++
        }
        val roundedTotalAmount = BigDecimal(totalAmount).setScale(
            1,
            RoundingMode.HALF_UP
        ).toDouble()
        val operations: TempBasalMicrobolusOperations =
            if (roundedTotalAmount == pumpType.bolusSize()) {
                val tempBasalList = LinkedBlockingDeque<TempBasalMicroBolusPair>()
                tempBasalList.add(
                    TempBasalMicroBolusPair(
                        0, roundedTotalAmount, totalAmount,
                        currentTime.plusMinutes(durationInMinutes / 2),
                        TempBasalMicroBolusPair.OperationType.BOLUS, callback
                    )
                )
                TempBasalMicrobolusOperations(
                    1, roundedTotalAmount,
                    durationInMinutes,
                    tempBasalList
                )
            } else if (roundedTotalAmount < pumpType.bolusSize()) {
                // T24: an extension of a running plan never cancels it (planClock set)
                if (planClock == null) cancelTempBasal(false, true)
                TempBasalMicrobolusOperations(
                    0, 0.0, 0,
                    LinkedBlockingDeque()
                )
            } else {
                buildTempBasalSMBOperations(
                    roundedTotalAmount, insulinPeriod, callback,
                    durationInMinutes, absoluteBasalValue
                )
            }
        if (basalType == PumpTempBasalType.Percent) {
            operations.absoluteRate = totalAmount
        } else {
            // the single-bolus plan (constructor) left it at 0.0: temporaryBasal then reported a 0 % TBR and
            // handlePumpStatus() stopped the running pump ("Stopping unstopped pump") after a TBR > 100 %
            operations.absoluteRate = absoluteBasalValue
        }
        return operations
    }

    private fun calculateTempBasalDosage(
        startedTime: Int,
        currentProfileValue: Profile.ProfileValue?,
        basalProfilesFromTemp: LinkedList<Profile.ProfileValue?>,
        percent: Int, abs: Double,
        remainingDurationInSeconds: Int,
        basalType: PumpTempBasalType,
    ): TempBasalMicroBolusDTO {
        var delta = 0
        if (basalProfilesFromTemp.isEmpty()) {
            delta = remainingDurationInSeconds
        } else if (basalProfilesFromTemp.peekFirst()!!.timeAsSeconds < startedTime) {
            if (basalProfilesFromTemp.peekFirst()!!.timeAsSeconds == 0) {
                delta = Constants.SECONDS_PER_DAY - startedTime
            }
        } else {
            delta = basalProfilesFromTemp.peekFirst()!!.timeAsSeconds - startedTime
        }
        val profileDosage = if (basalType == PumpTempBasalType.Percent) {
            calcTotalAmountPct(percent, currentProfileValue!!.value, delta / 60)
        } else {
            calcTotalAmountAbs(abs, currentProfileValue!!.value, delta / 60)
        }
        return createTempBasalPair(
            profileDosage,
            delta,
            currentProfileValue.timeAsSeconds,
            basalType
        )
    }

    private fun createTempBasalPair(
        totalAmount: Double, durationInSeconds: Int,
        startTime: Int, basalType: PumpTempBasalType,
    ): TempBasalMicroBolusDTO {
        return TempBasalMicroBolusDTO(
            totalAmount, basalType == PumpTempBasalType.Percent, durationInSeconds / 60,
            startTime, startTime + durationInSeconds
        )
    }

    private fun calcTotalAmountPct(
        tempBasal: Int, currentBasalValue: Double,
        durationInMinutes: Int,
    ): Double {
        var tempBasal = tempBasal
        if (tempBasal > 100) {
            tempBasal -= 100
        }
        return tempBasal / 100.0 * currentBasalValue * (durationInMinutes / 60.0)
    }

    private fun calcTotalAmountAbs(
        tempBasal: Double, currentBasalValue: Double,
        durationInMinutes: Int,
    ): Double {
        return (tempBasal - currentBasalValue) * (durationInMinutes / 60.0)
    }

//    private fun calcTotalAmount(percent: Int, value: Double, durationInMinutes: Int): Double {
//        return (percent.toDouble() / 100 - 1) * value * (durationInMinutes / 60.0)
//    }

    open val currentTime: LocalDateTime
        get() = planClock ?: LocalDateTime.now()

    private fun buildTempBasalSMBOperations(
        totalAmount: Double,
        insulinPeriod: List<TempBasalMicroBolusDTO>,
        callback: Function1<*, *>,
        durationInMinutes: Int,
        absoluteRate: Double,
    ): TempBasalMicrobolusOperations {
        val result = TempBasalMicrobolusOperations()
        result.durationInMinutes = durationInMinutes
        result.absoluteRate = absoluteRate
        var operationTime = currentTime
        var minDosage = 0.0
        for (period in insulinPeriod) {
            val periodDose = period.insulinRate // + accumulatedNextPeriodDose;
            val roundedPeriodDose =
                BigDecimal(periodDose).setScale(1, RoundingMode.HALF_DOWN).toDouble()
            val time = (period.endTimeInSeconds - period.sartTimeInSeconds) / 60
            val periodAvailableOperations: Int = time / Constants.INTERVAL_BETWEEN_OPERATIONS
            aapsLogger.info(LTag.PUMPBTCOMM, "" + time)
            aapsLogger.info(LTag.PUMPBTCOMM, "" + periodAvailableOperations)
            val minBolusDose = pumpType.bolusSize()
            if (roundedPeriodDose >= minBolusDose) {
                val doses = BigDecimal(
                    roundedPeriodDose / minBolusDose
                ).setScale(0, RoundingMode.HALF_DOWN).toInt()
                val calculatedDose =
                    BigDecimal(periodDose).divide(BigDecimal(doses), 2, RoundingMode.HALF_DOWN)
                        .toDouble()
                aapsLogger.info(LTag.PUMP, periodDose.toString())
                aapsLogger.info(LTag.PUMP, calculatedDose.toString())
                minDosage =
                    BigDecimal(periodDose / doses).setScale(1, RoundingMode.HALF_DOWN).toDouble()
                val list = buildOperations(doses.toDouble(), periodAvailableOperations, emptyList())
                //TODO convert build operations to return list of tempmicroboluspair
                for (dose in list) {
                    if (dose > 0) {
                        val pair = TempBasalMicroBolusPair(
                            0,
                            dose, calculatedDose, operationTime,
                            TempBasalMicroBolusPair.OperationType.BOLUS, callback
                        )
                        aapsLogger.info(LTag.EVENTS, pair.toString())
                        result.operations.add(pair)
                    }
                    operationTime = if (currentTime.isAfter(operationTime)) {
                        val refresh =
                            medLinkStatusRefreshMap[MedLinkMedtronicStatusRefreshType.PumpHistory]
                        if (refresh!! > currentTime.plusMinutes(5).toDateTime().millis) {
                            operationTime.plusMinutes(Constants.INTERVAL_BETWEEN_OPERATIONS)
                        } else {
                            val instant = Instant.ofEpochMilli(refresh)
                            instant.toDateTime().withZone(DateTimeZone.getDefault())
                                .toLocalDateTime()
                        }
                    } else {
                        operationTime.plusMinutes(Constants.INTERVAL_BETWEEN_OPERATIONS)
                    }
                }
            }
        }
        val totalDose = result.operations.stream().map { obj: TempBasalMicroBolusPair -> obj.dose }
            .reduce(0.0) { a: Double, b: Double -> java.lang.Double.sum(a, b) }
        val doseDiff = totalDose - totalAmount
        if (totalDose.compareTo(totalAmount) > 0 && Math.abs(doseDiff - minDosage) < 1E-2) {
            result.operations = excludeExtraDose(totalDose, totalAmount, result)
            aapsLogger.info(
                LTag.AUTOMATION,
                "Error in temp basal microbolus calculation, totalDose: $totalDose, totalAmount $totalAmount, profiles $insulinPeriod, absoluteRate: $absoluteRate, insulin period$insulinPeriod"
            )
        } else if (totalAmount.compareTo(totalDose) > 0 && Math.abs(doseDiff) >= minDosage) {
            //TODO need a test to verify if this is reacheable
            aapsLogger.info(
                LTag.AUTOMATION,
                "Error in temp basal microbolus calculation, totalDose: $totalDose, totalAmount $totalAmount, profiles $insulinPeriod, absoluteRate: $absoluteRate, insulin period$insulinPeriod"
            )
            //            throw new RuntimeException("Error in temp basal microbolus calculation, totalDose: " + totalDose + ", totalAmount " + totalAmount + ", profiles " + insulinPeriod);
        }
        return result
    }

    private fun excludeExtraDose(
        totalDose: Double,
        totalAmount: Double,
        result: TempBasalMicrobolusOperations,
    ): LinkedBlockingDeque<TempBasalMicroBolusPair> {
        var dosesToDecrease = round((totalDose - totalAmount) / pumpType.bolusSize()).toInt()
        val maxDosage = result.operations.stream().map { obj: TempBasalMicroBolusPair -> obj.dose }
            .max { obj: Double, anotherDouble: Double? -> obj.compareTo(anotherDouble!!) }
            .orElse(0.0)
        val minDosage = result.operations.stream().map { obj: TempBasalMicroBolusPair -> obj.dose }
            .min { obj: Double, anotherDouble: Double? -> obj.compareTo(anotherDouble!!) }
            .orElse(0.0)
        var operations = LinkedBlockingDeque<TempBasalMicroBolusPair>()
        if (maxDosage == minDosage) {
            val sortedOperations = result.operations.stream()
                .sorted { prev: TempBasalMicroBolusPair, curr: TempBasalMicroBolusPair ->
                    prev.delta.compareTo(curr.delta)
                }
            operations = sortedOperations.skip(dosesToDecrease.toLong())
                .sorted { prev: TempBasalMicroBolusPair, curr: TempBasalMicroBolusPair ->
                    prev.releaseTime.compareTo(curr.releaseTime)
                }
                .collect(
                    Collectors.toCollection(
                        Supplier<LinkedBlockingDeque<TempBasalMicroBolusPair>> { LinkedBlockingDeque() })
                )
        } else {
            while (!result.operations.isEmpty()) {
                val tmp = result.operations.pollFirst()!!
                if (tmp.dose == maxDosage && dosesToDecrease > 0) {
                    dosesToDecrease -= 1
                    if (tmp.dose.compareTo(pumpType.bolusSize()) > 0) {
                        operations.add(tmp.decreaseDosage(pumpType.bolusSize()))
                    }
                } else {
                    operations.add(tmp)
                }
            }
        }
        return operations
    }

    private fun buildOperationsList(doses: Double, operations: Int, dose: Double): List<Double> {
        val result = DoubleArray(operations)
        Arrays.fill(result, dose)
        return buildOperations(doses - operations, operations, result.asList())
    }

    fun buildOperations(doses: Double, operations: Int, list: List<Double>): List<Double> {
        var list: MutableList<Double> = list.toMutableList()
        if (list.isEmpty()) {
            val values = DoubleArray(operations)
            Arrays.fill(values, 0.0)
            list = values.asList().toMutableList()
        }
        return if (doses == 0.0 || operations == 0) {
            list
        } else if (doses > operations) {
            buildOperationsList(doses, operations, list[0] + pumpType.bolusSize())
        } else {
            val step = operations.toDouble() / doses
            if (doses == 1.0) {
                var position = Math.floorDiv(operations, 2) - 1
                if (position < 0) {
                    position = 0
                }
                list[position] = list[position] + pumpType.bolusSize()
                list[position] = BigDecimal(list[position]).setScale(
                    1,
                    RoundingMode.HALF_UP
                ).toDouble()
                return list
            }
            if (step < 2.5) {
                buildSmallStepsTempSMBDosage(operations, doses, list, step)
            } else {
                buildBigStepsTempSMBDosage(operations, doses, list, step)
            }
        }
    }

    private fun buildBigStepsTempSMBDosage(
        operations: Int,
        doses: Double,
        list: MutableList<Double>,
        step: Double
    ): List<Double> {
        var doses = doses
        var nextStep = step
        list[0] = list[0] + pumpType.bolusSize()
        doses--
        for (index in 1 until operations) {
            if (doses > 0 && nextStep < index) {
                doses--
                list[index] = BigDecimal(list[index] + pumpType.bolusSize()).setScale(
                    1,
                    RoundingMode.HALF_UP
                ).toDouble()
                nextStep = index + step
            }
        }
        return if (doses == 0.0) {
            list
        } else {
            aapsLogger.info(LTag.PUMPBTCOMM, "unreachable?")
            //TODO unreachable code
            buildOperations(doses, operations, list)
        }
    }

    private fun buildSmallStepsTempSMBDosage(
        operations: Int,
        doses: Double,
        list: List<Double>,
        step: Double
    ): List<Double> {
        val diff = operations - doses
        return when (diff) {
            3.0 -> {
                val newStep = java.lang.Double.valueOf(Math.floor(operations * 0.33)).toInt()
                val secondPosition = 2 * newStep
                val thirdPosition = 3 * newStep
                fillTempSMBWithExclusions(list, newStep, secondPosition, thirdPosition)
            }

            2.0 -> {
                val newStep = java.lang.Double.valueOf(Math.floor(operations * 0.25)).toInt()
                val half = round(operations / diff).toInt()
                val secondPosition = half + newStep
                fillTempSMBWithExclusions(list, newStep, secondPosition)
            }

            1.0 -> {
                fillTempSMBWithExclusions(list, Math.floorDiv(operations, 2))
            }

            else -> {
                fillTempSMBWithStep(list, step, doses)
            }
        }
    }

    private fun fillTempSMBWithStep(list: List<Double>, step: Double, doses: Double): List<Double> {
        var doses = doses
        val result: MutableList<Double> = ArrayList()
        var index = 0
        var stepIndex = 0
        for (value in list) {
            val currentStep = step * stepIndex
            if (doses == 0.0) {
                result.add(value)
            } else if (index == 0) {
                stepIndex++
                result.add(value + pumpType.bolusSize())
                doses--
            } else if (currentStep <= index) {
                stepIndex++
                result.add(value + pumpType.bolusSize())
                doses--
            } else {
                result.add(value)
            }
            index++
        }
        return result
    }

    private fun fillTempSMBWithExclusions(
        list: List<Double>,
        vararg exclusions: Int
    ): List<Double> {
        val result: MutableList<Double> = ArrayList()
        var index = 0
        for (value in list) {
            if (!exclusions.contains(index)) {
                result.add(
                    BigDecimal(value + pumpType.bolusSize()).setScale(
                        1,
                        RoundingMode.HALF_DOWN
                    ).toDouble()
                )
            } else {
                result.add(value)
            }
            index++
        }
        return result
    }

    private fun scheduleTempBasalBolus(
        percent: Int,
        durationInMinutes: Int,
        profile: Profile,
        callback: Function1<*, *>,
        absoluteBasalValue: Double,
        basalType: PumpTempBasalType,
        previousFirst: TempBasalMicroBolusPair?,
    ): PumpEnactResult {
        val currentTime = currentTime
        val currentTas = currentTime.millisOfDay / 1000
        val endTas = currentTas + durationInMinutes * 60L
        val basalProfilesFromTemp = extractTempProfiles(profile, endTas, currentTas)
        tempBasalMicrobolusOperations = buildFirstLevelTempBasalMicroBolusOperations(
            percent,
            basalProfilesFromTemp, durationInMinutes, callback, absoluteBasalValue, basalType
        )
        tempBasalMicrobolusOperations.operations =
            applyPlanCarry(tempBasalMicrobolusOperations.operations, previousFirst, durationInMinutes)
        markPlanMade(if (basalType == PumpTempBasalType.Absolute) MedLinkTbrKeepRule.percentOf(absoluteBasalValue, profile.getBasal()) else percent)
        refreshAnyStatusThatNeedsToBeRefreshed()
        return buildPumpEnactResult().enacted(true).success(true)
            .comment(rh.gs(R.string.medtronic_cmd_desc_set_tbr))
    }

    /**
     * Applies [MedLinkTbrPlanCarry] to a new plan: the first operation is not moved later by a new TBR in the
     * same direction, and TBR boluses keep the spacing of the TBR rate after the last TBR bolus.
     * @param previousFirst first operation of the old plan (before it was cleared)
     * @return the plan with the new release times, without the dropped boluses
     */
    private fun applyPlanCarry(
        operations: LinkedBlockingDeque<TempBasalMicroBolusPair>,
        previousFirst: TempBasalMicroBolusPair?,
        durationInMinutes: Int
    ): LinkedBlockingDeque<TempBasalMicroBolusPair> {
        if (operations.isEmpty()) return operations
        val ops = operations.toList()
        val now = System.currentTimeMillis()
        val pending = previousFirst?.takeIf { !it.isCommandIssued }
        val lastTbrBolus =
            if (ops.first().operationType == TempBasalMicroBolusPair.OperationType.BOLUS) lastTbrBolus(previousFirst, now) else null
        val times = MedLinkTbrPlanCarry.releaseTimes(
            ops.map { it.toCarryOp() }, pending?.toCarryOp(), lastTbrBolus, durationInMinutes, pumpType.bolusSize(), now
        )
        val result = LinkedBlockingDeque<TempBasalMicroBolusPair>()
        ops.forEachIndexed { index, op ->
            val at = times[index]
            if (at == null) {
                aapsLogger.info(LTag.PUMP, "TBR plan: ${op.toStringView()} dropped (after the end of the TBR, spacing after the last TBR bolus)")
            } else {
                if (at != op.releaseTime.toDate().time) {
                    val from = op.releaseTime.toString("HH:mm:ss")
                    op.releaseTime = LocalDateTime(at)
                    aapsLogger.info(LTag.PUMP, "TBR plan: ${op.operationType} moved from $from to ${op.releaseTime.toString("HH:mm:ss")}")
                }
                result.add(op)
            }
        }
        return result
    }

    /** Last TBR bolus: the newest one in the database, or the one of the old plan that is being sent now. */
    private fun lastTbrBolus(previousFirst: TempBasalMicroBolusPair?, now: Long): MedLinkTbrPlanCarry.LastBolus? {
        val saved = runBlocking { persistenceLayer.getNewestBolusOfType(BS.Type.TBR) }
            ?.let { MedLinkTbrPlanCarry.LastBolus(it.timestamp, it.amount) }
        val sending = previousFirst
            ?.takeIf { it.isCommandIssued && it.operationType == TempBasalMicroBolusPair.OperationType.BOLUS }
            ?.let { MedLinkTbrPlanCarry.LastBolus(now, it.dose) }
        return listOfNotNull(saved, sending).maxByOrNull { it.at }
    }

    private fun TempBasalMicroBolusPair.toCarryOp() =
        MedLinkTbrPlanCarry.Op(operationType, releaseTime.toDate().time, dose, calculatedDose)

    private fun extractTempProfiles(
        profile: Profile,
        endTas: Long,
        currentTas: Int
    ): LinkedList<Profile.ProfileValue?> {
        val tempBasalProfiles = LinkedList<Profile.ProfileValue?>()
        var previousProfile: Profile.ProfileValue? = null
        val basalValues = cloneProfileValue(profile)
        for (basalValue in basalValues) {
            if (endTas < basalValue!!.timeAsSeconds) {
                if (previousProfile == null) {
                    tempBasalProfiles.add(basalValue)
                }
                break
            }
            if (currentTas <= basalValue.timeAsSeconds) {
                if (tempBasalProfiles.isEmpty()) {
                    if (currentTas < basalValue.timeAsSeconds) {
                        tempBasalProfiles.add(previousProfile)
                    }
                    tempBasalProfiles.add(basalValue)
                } else if (previousProfile == null || basalValue.value != previousProfile.value) {
                    tempBasalProfiles.add(basalValue)
                }
            }
            previousProfile = basalValue
        }
        if (tempBasalProfiles.isEmpty()) {
            if (previousProfile!!.timeAsSeconds < endTas && previousProfile.timeAsSeconds < currentTas) {
                tempBasalProfiles.add(previousProfile)
            }
        }
        if (endTas >= Constants.SECONDS_PER_DAY) {
            if (tempBasalProfiles.isEmpty() && previousProfile != null) {
                previousProfile.timeAsSeconds = currentTas
                tempBasalProfiles.add(previousProfile)
            }
            tempBasalProfiles.addAll(
                extractTempProfiles(
                    profile,
                    endTas - Constants.SECONDS_PER_DAY,
                    0
                )
            )
        }
        return tempBasalProfiles
    }

    private fun cloneProfileValue(profile: Profile): Array<Profile.ProfileValue?> {
        val basalValues = profile.getBasalValues()
        val cloned = arrayOfNulls<Profile.ProfileValue>(basalValues.size)
        for (index in cloned.indices) {
            val profileValue = basalValues[index]
            cloned[index] = Profile.ProfileValue(profileValue.timeAsSeconds, profileValue.value)
        }
        return cloned
    }

    protected open fun buildPumpEnactResult(): PumpEnactResult {
        return pumpEnactResultProvider.get()
    }

//    fun setTempBasalPercent(
//        percent: Int?,
//        durationInMinutes: Int?,
//        profile: Profile?,
//        enforceNew: Boolean
//    ): PumpEnactResult {
//        aapsLogger.debug(LTag.PUMP, "setTempBasalPercent [PumpPluginAbstract] - Not implemented.")
//        return pumpEnactResultProvider.get().success(false).enacted(false).comment(
//            rh.gs(
//                app.aaps.pump.common.R.string.pump_operation_not_supported_by_pump_driver
//            )
//        )
//    }

    override fun setTempBasalPercent(
        requestedPercent: Int, durationInMinutes: Int,
        profile: Profile, enforceNew: Boolean, func: Function1<PumpEnactResult, *>,
    ): PumpEnactResult {
        val percent = if (requestedPercent < 100) {
            val base = medLinkBaseBasalRate.takeIf { it > 0.0 } ?: profile.getBasal()
            val raised = postBolusMinBasal(base * requestedPercent / 100.0, profile)
            maxOf(requestedPercent, kotlin.math.round(raised / base * 100.0).toInt())
        } else requestedPercent
        // kept before the old plan is cleared: a new TBR must not move the first operation later
        val previousFirst = tempBasalMicrobolusOperations.operations.peekFirst()
        if (temporaryBasal != null) {

            aapsLogger.info(LTag.PUMPBTCOMM, temporaryBasal.toString())

            val previousBasal = temporaryBasal
            if ((medLinkPumpStatus.pumpRunningState.isSuspended() ||
                        (previousBasal?.desiredPct != null && previousBasal.desiredPct!! < 100)) && percent >= 100
            ) {
                aapsLogger.info(LTag.EVENTS, "temp basal starting pump")

                startPump(object : Callback() {
                    override fun run() {}
                }, true)
            } else if (percent == 0) {
                createTemporaryBasalData(durationInMinutes, percent.toDouble())
            }
        }
        // T24: a TBR with the same percent as the running plan keeps it (MedLinkTbrKeepRule)
        val kept = percent > 0 && percent != 100 && keepTbrPlanIfSame(percent, durationInMinutes, profile, func)
        if (!kept) tempBasalMicrobolusOperations.clearOperations()
        val result: PumpEnactResult = when {
            kept -> {
                buildPumpEnactResult().enacted(true).success(true).comment(rh.gs(R.string.medtronic_cmd_desc_set_tbr))
            }

            percent == 100 -> {
                clearTempBasal()
            }

            percent < 100 -> {
                scheduleSuspension(
                    percent, durationInMinutes, profile, func, 0.0,
                    PumpTempBasalType.Percent, previousFirst
                )!!
            }

            else -> {
                scheduleTempBasalBolus(
                    percent, durationInMinutes, profile, func,
                    0.0, PumpTempBasalType.Percent, previousFirst
                )
            }
        }
        //        result.success = false;
        //        result.comment = MainApp.gs(R.string.pumperror);
        aapsLogger.info(LTag.PROFILE, "Settings temp basal percent: $result")
        // The queue waits for this answer (AAPS 4.0), see setTempBasalAbsolute. Only the first answer counts.
        func(result)
        return result
    }

    // fun setExtendedBolus(insulin: Double?, durationInMinutes: Int?): PumpEnactResult {
    //     val result = buildPumpEnactResult()
    //     result.success = false
    //     medtronicUtil.sendNotification(MedtronicNotificationType.PumpExtendedBolusNotEnabled, rh, rxBus)
    //     aapsLogger.debug("Setting extended bolus: $result")
    //     return result
    // }
    override fun cancelTempBasal(
        isStartCommand: Boolean,
        enforceNew: Boolean,
        callback: Callback?
    ): PumpEnactResult {
        if (isStartCommand && (!tempBasalMicrobolusOperations.shouldBeSuspended() || (temporaryBasal != null && temporaryBasal?.rate!! >= 0))) {
            return pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(true) //
                .comment(app.aaps.pump.common.hw.rileylink.R.string.medtronic_pump_status)
        } else return cancelTempBasal(enforceNew, callback)
    }

    fun cancelTempBasal(enforceNew: Boolean, callback: Callback?): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, logPrefix + "cancelTempBasal - started")

        if (isPumpNotReachable) {
            setRefreshButtonEnabled(true)
            aapsLogger.info(
                LTag.PUMP,
                "MedtronicPumpPlugin::checkTimeAndOptionallySetTime - Pump Unreachable."
            )
            return pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(false) //
                .comment(R.string.medtronic_pump_status_pump_unreachable)
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        setRefreshButtonEnabled(false)
        val tbrCurrent = readTBR()
        if (tbrCurrent.durationMinutes > 0) {
            if (tbrCurrent.insulinRate > 0.0f) {
                aapsLogger.info(LTag.PUMP, logPrefix + "cancelTempBasal - TBR already canceled.")
                finishAction("TBR")

            }
        } else {
            aapsLogger.warn(
                LTag.PUMP,
                logPrefix + "cancelTempBasal - Could not read current TBR, canceling operation."
            )
            finishAction("TBR")
        }
        // B12: one read of the first step (another thread can take it)
        val first = tempBasalMicrobolusOperations.firstOrNull()
        if (first != null) {
            if ((first.operationType == TempBasalMicroBolusPair.OperationType.REACTIVATE && !first.isCommandIssued) ||
                (first.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND && first.isCommandIssued) ||
                currentPumpStatus.isSuspended()
            ) {
                startPump(callback, true)
            }
            tempBasalMicrobolusOperations.clearOperations()
        }

        // val responseTask2 = medLinkService?.medtronicUIComm?.executeCommand(MedtronicCommandType.CancelTBR)
        // val response = responseTask2?.result as Boolean?
        // finishAction("TBR")
        // return if(tbrCurrent.){
        //     aapsLogger.info(LTag.PUMP, logPrefix + "cancelTempBasal - Cancel TBR failed.")
        //     startPump(callback)
        //     return
        // } else {
        //     aapsLogger.info(LTag.PUMP, logPrefix + "cancelTempBasal - Cancel TBR successful.")
        //
        //
        //     //cancelTBRWithTemporaryId()
        //
        //     return
        // }
        return pumpEnactResultProvider.get() //
            .success(false) //
            .enacted(true) //
            .comment(app.aaps.pump.common.hw.rileylink.R.string.medtronic_pump_status)
    }

    override fun extendBasalTreatment(
        duration: Int,
        callback: Function1<PumpEnactResult, *>
    ): PumpEnactResult {
        //TODO implement
        val result = pumpEnactResultProvider.get().success(true).enacted(true)
            .comment(rh.gs(app.aaps.core.ui.R.string.let_temp_basal_run))
        createTemporaryBasalData(duration, 0.0);
        val reactivateOper =
            tempBasalMicrobolusOperations.operations.stream().filter { f: TempBasalMicroBolusPair ->
                f.operationType ==
                        TempBasalMicroBolusPair.OperationType.REACTIVATE
            }.findFirst()
        if (reactivateOper.isPresent) {
            reactivateOper.get().setReleaseTime(duration)
            if (medLinkPumpStatus.runningTBR != null) {
                medLinkPumpStatus.runningTBR?.durationInSeconds = duration * 60
                pumpSyncStorage.pumpSyncStorageTBR.add(medLinkPumpStatus.runningTBR!!)
                pumpSyncStorage.saveStorageTBR()
            }
            callback.invoke(result)
        } else {

        }
        return result
    }

    // override fun cancelExtendedBolus(): PumpEnactResult {
    //     val result = pumpEnactResultProvider.get()
    //     result.success = false
    //     medtronicUtil.sendNotification(MedtronicNotificationType.No, rh, rxBus)
    //     aapsLogger.debug("Canceling extended bolus: $result")
    //     return result
    // }

    override fun manufacturer(): ManufacturerType {
        return ManufacturerType.Medtronic
    }

    override fun generateTempId(dataObject: Any): Long {
        val timestamp: Long = dataObject as Long
        return DateTimeUtil.toATechDate(timestamp)
    }

    override val lastBolusTime: StateFlow<Long?> by lazy { medLinkPumpStatus.lastBolusTimeFlow.mapState { it?.time } }
    override val lastConnection: Long
        get() = medLinkPumpStatus.lastConnection

    override fun model(): PumpType {
        return pumpDescription.pumpType
    }

    //    @Override public PumpType getPumpType() {
    //        return pumpDescription.pumpType;
    //    }
    override fun serialNumber(): String {
        return medLinkPumpStatus.serialNumber
    }

    // override fun shortStatus(veryShort: Boolean): String {
    //     var ret = ""
    //     if (pumpStatusData.lastConnection != 0L) {
    //         val agoMsec = System.currentTimeMillis() - pumpStatusData.lastConnection
    //         val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
    //         ret += "LastConn: $agoMin min agon"
    //     }
    //     if (pumpStatusData.lastBolusTime != null && pumpStatusData.lastBolusTime!!.time != 0L) {
    //         ret += """
    //             LastBolus: ${to2Decimal(pumpStatusData.lastBolusAmount!!)}U @${DateFormat.format("HH:mm", pumpStatusData.lastBolusTime)}
    //
    //             """.trimIndent()
    //     }
    //     val activeTemp: TemporaryBasal = activePlugin.getActiveTreatments().getRealTempBasalFromHistory(System.currentTimeMillis())
    //     if (activeTemp != null) {
    //         ret += """
    //             Temp: ${activeTemp.toStringFull().toString()}
    //
    //             """.trimIndent()
    //     }
    //     val activeExtendedBolus: ExtendedBolus = activePlugin.getActiveTreatments().getExtendedBolusFromHistory(
    //         System.currentTimeMillis()
    //     )
    //     if (activeExtendedBolus != null) {
    //         ret += """
    //             Extended: ${activeExtendedBolus.toString().toString()}
    //
    //             """.trimIndent()
    //     }
    //     // if (!veryShort) {
    //     // ret += "TDD: " + DecimalFormatter.to0Decimal(pumpStatus.dailyTotalUnits) + " / "
    //     // + pumpStatus.maxDailyTotalUnits + " Un";
    //     // }
    //     ret += """
    //         IOB: ${pumpStatusData.iob}U
    //
    //         """.trimIndent()
    //     ret += """
    //         Reserv: ${to0Decimal(pumpStatusData.reservoirLevel)}U
    //
    //         """.trimIndent()
    //     ret += """
    //         Batt: ${pumpStatusData.batteryVoltage}
    //
    //         """.trimIndent()
    //     return ret
    // }

    override fun getCustomActions(): List<CustomAction>? {
        if (customActions == null) {
            customActions = Arrays.asList(
                customActionClearBolusBlock,
                customActionSuspendPump
            )
        }
        return customActions
    }

    override fun executeCustomAction(customActionType: CustomActionType) {
        val mcat = customActionType as MedtronicCustomActionType
        when (mcat) {
            MedtronicCustomActionType.WakeUpAndTune -> {
                if (medLinkMedtronicService?.verifyConfiguration() == true && activePlugin != null) {
                    serviceTaskExecutor.startTask(WakeAndTuneTask(activePlugin!!, rxBus))
                } else {
                    aapsLogger.debug("Medronic Pump plugin intent")
                    uiInteraction.runAlarm(
                        rh.gs(R.string.medtronic_error_operation_not_possible_no_configuration),
                        rh.gs(R.string.medtronic_warning),
                        app.aaps.core.ui.R.raw.boluserror
                    )
                }
            }

            MedtronicCustomActionType.MedLinkSuspendPump -> {
                medLinkScope.launch {
                    val result = commandQueue.stopPump()
                    aapsLogger.info(LTag.PUMP, "user suspend result: ${result.success}")
                }
            }

            MedtronicCustomActionType.ClearBolusBlock -> {
                busyTimestamps.clear()

                //                this.customActionClearBolusBlock.setEnabled(false);
                refreshCustomActionsList()
            }

            MedtronicCustomActionType.ResetRileyLinkConfiguration -> {
//                if (activePlugin != null) {
//                    serviceTaskExecutor.startTask(ResetRileyLinkConfigurationTask(activePlugin, rxBus, ))
//                }
            }

            else -> {}
        }
    }


    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, logPrefix + "setNewBasalProfile")

        // this shouldn't be needed, but let's do check if profile setting we are setting is same as current one
        if (isThisProfileSet(profile)) {
            return pumpEnactResultProvider.get() //
                .success(true) //
                .enacted(true) //
                .comment(rh.gs(R.string.medtronic_cmd_basal_profile_not_set_is_same))
        }
        setRefreshButtonEnabled(false)
        if (isPumpNotReachable) {
            setRefreshButtonEnabled(true)
            aapsLogger.info(LTag.PUMP, "pump unreachable")
            return pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(false) //
                .comment(rh.gs(R.string.medtronic_pump_status_pump_unreachable))
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        val basalProfile = convertProfileToMedtronicProfile(profile)
        val profileInvalid = isProfileValid(basalProfile)
        if (profileInvalid != null) {
            return pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(false) //
                .comment(rh.gs(R.string.medtronic_cmd_set_profile_pattern_overflow, profileInvalid))
        }
        runOnUiThread(Runnable() {
            fun run() {
                Toast.makeText(
                    context,
                    rh.gs(R.string.medtronic_cmd_basal_profile_could_not_be_set, 40),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        //        MedtronicUITask responseTask = medLinkService.getMedtronicUIComm().executeCommand(MedLinkMedtronicCommandType.SetBasalProfileSTD,
        //                basalProfile);
        //
        //        Boolean response = (Boolean) responseTask.returnData;
        //
        //        getAapsLogger().info(LTag.PUMP, getLogPrefix() + "Basal Profile was set: " + response);
        //
        //        if (response) {
        return pumpEnactResultProvider.get().success(false).enacted(false)
        //        } else {
        //            return new PumpEnactResult(getInjector()).success(response).enacted(response) //
        //                    .comment(getRh().gs(R.string.medtronic_cmd_basal_profile_could_not_be_set));
        //        }
    }

    private fun isProfileValid(basalProfile: BasalProfile): String? {
        val stringBuilder = StringBuilder()
        if (medLinkPumpStatus.maxBasal == null) return null
        for (profileEntry in basalProfile.getEntries()) {
            if (profileEntry.rate > medLinkPumpStatus.maxBasal!!) {
                stringBuilder.append(profileEntry.startTime!!.toString("HH:mm"))
                stringBuilder.append("=")
                stringBuilder.append(profileEntry.rate)
            }
        }
        return if (stringBuilder.length == 0) null else stringBuilder.toString()
    }

    private fun convertProfileToMedtronicProfile(profile: Profile?): BasalProfile {
        val basalProfile = BasalProfile(aapsLogger)
        for (i in 0..23) {
            val rate = profile!!.getBasalTimeFromMidnight(i * 60 * 60)
            val v = pumpDescription.pumpType.determineCorrectBasalSize(rate)
            val basalEntry = BasalProfileEntry(v, i, 0)
            basalProfile.addEntry(basalEntry)
        }
        basalProfile.generateRawDataFromEntries()
        return basalProfile
    }

    private fun setNotReachable(isBolus: Boolean, success: Boolean): PumpEnactResult {
        setRefreshButtonEnabled(true)
        if (isBolus) {
            bolusDeliveryType = BolusDeliveryType.Idle
        }
        return if (success) {
            pumpEnactResultProvider.get() //
                .success(true) //
                .enacted(false)
        } else {
            aapsLogger.info(LTag.PUMP, "pump unreachable")
            pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(false) //
                .comment(rh.gs(R.string.medtronic_pump_status_pump_unreachable))
        }
    }

    private val customActionSuspendPump = CustomAction(
        R.string.medlink_custom_action_suspend_pump,
        MedtronicCustomActionType.MedLinkSuspendPump,
        IcLoopPausedPump,
        true
    )

    private val customActionClearBolusBlock = CustomAction(
        R.string.medtronic_custom_action_clear_bolus_block,
        MedtronicCustomActionType.ClearBolusBlock,
        IcLoopPausedPump,
        false
    )

    private fun setEnableCustomAction(customAction: MedtronicCustomActionType, isEnabled: Boolean) {
        if (customAction === MedtronicCustomActionType.ClearBolusBlock) {
            customActionClearBolusBlock.isEnabled = isEnabled
        } else if (customAction === MedtronicCustomActionType.ResetRileyLinkConfiguration) {
            //TODO see if medlink will need this resetconfig
            //            this.customActionResetRLConfig.setEnabled(isEnabled);
        }
        refreshCustomActionsList()
    }

    override fun deliverBolus(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        aapsLogger.info(
            LTag.PUMP,
            "MedtronicPumpPlugin::deliverBolus - " + BolusDeliveryType.DeliveryPrepared
        )
        setRefreshButtonEnabled(false)
        if (detailedBolusInfo.insulin > medLinkPumpStatus.reservoirRemainingUnits) {
            return pumpEnactResultProvider.get() //
                .success(false) //
                .enacted(false) //
                .comment(
                    rh.gs(
                        R.string.medtronic_cmd_bolus_could_not_be_delivered_no_insulin,
                        medLinkPumpStatus.reservoirRemainingUnits,
                        detailedBolusInfo.insulin
                    )
                )
        }
        bolusDeliveryType = BolusDeliveryType.DeliveryPrepared
        if (isPumpNotReachable) {
            aapsLogger.info(LTag.PUMP, "MedtronicPumpPlugin::deliverBolus - Pump Unreachable.")
            return setNotReachable(true, false)
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        if (bolusDeliveryType == BolusDeliveryType.CancelDelivery) {
            return setNotReachable(true, true)
        }

        return if (bolusDeliveryType == BolusDeliveryType.CancelDelivery) {
            setNotReachable(true, true)
        } else try {
            bolusDeliveryType = BolusDeliveryType.Delivering

            val response = AtomicReference(false)
            // val bolusTimesstamp = 0L
            val bolusCallback = BolusCallback(aapsLogger, detailedBolusInfo)
            val andThem: Function<Supplier<Stream<String>>, MedLinkStandardReturn<String>> =
                bolusCallback.andThen { f: MedLinkStandardReturn<BolusAnswer> ->
                    val answer = f.functionResult
                    if (answer.response == PumpResponses.UnknownAnswer) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "need to check bolus")
                        //                    processDeliveredBolus(answer, detailedBolusInfo);
                        bolusHistoryCheck = true
                    }
                    if (f.functionResult.answer != null) {
                        MedLinkStandardReturn(f.answer, f.functionResult.answer!!)
                    } else {
                        aapsLogger.info(LTag.PUMPBTCOMM, "empty answer")
                        MedLinkStandardReturn(f.answer, "")
                    }
                }
            val bolusCommand = when (detailedBolusInfo.bolusType) {
                BS.Type.TBR -> MedLinkCommandType.TBRBolus
                BS.Type.SMB -> MedLinkCommandType.SMBBolus
                else -> MedLinkCommandType
                    .Bolus
            }
            // no M queued with the bolus: the tracker reads M only to follow a bolus being delivered
            val bolusMessage: BolusStatusMedLinkMessage<String>? = null

            val msg = BolusMedLinkMessage(
                bolusCommand,
                detailedBolusInfo,
                andThem, bolusMessage,
                btSleepTime,
                BleBolusCommand(
                    aapsLogger,
                    medLinkMedtronicService?.medLinkServiceData,
                    this,
                    detailedBolusInfo, bolusMessage != null
                ),
                tempBasalMicrobolusOperations.shouldBeSuspended()
            )
            val startStopCommands =
                buildStartStopCommands(detailedBolusInfo.bolusType == BS.Type.SMB)
            if (startStopCommands.isEmpty()) {
                medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)
            } else {
                medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(
                    MedLinkPumpMessage<PumpDriverState, Any>(
                        startStopCommands,
                        btSleepTime
                    ), msg
                )
            }
            setRefreshButtonEnabled(true)

            //            int count = 0;
            //            while ((!isPumpNotReachable() || bolusTimesstamp == 0l) && count < 15) {
            //                SystemClock.sleep(5000);
            //                count++;
            //            }
            //            // LOG.debug("MedtronicPumpPlugin::deliverBolus - Response: {}", response);
            if (response.get() && !isPumpNotReachable) {
                if (bolusDeliveryType == BolusDeliveryType.CancelDelivery) {
                    // LOG.debug("MedtronicPumpPlugin::deliverBolus - Delivery Canceled after Bolus started.");
                    Thread {
                        SystemClock.sleep(2000)
                        uiInteraction.runAlarm(
                            rh.gs(R.string.medtronic_cmd_cancel_bolus_not_supported),
                            rh.gs(R.string.medtronic_warning),
                            app.aaps.core.ui.R.raw.boluserror
                        )
                    }.start()
                }
                aapsLogger.info(LTag.PUMP,"delivery at the latest set")
                val now = System.currentTimeMillis()

                aapsLogger.info(LTag.PUMP, Date(now).toString())
                //TODO was deliveryatthelatest before
                detailedBolusInfo.timestamp = now

                //                activePlugin.getActiveTreatments().addToHistoryTreatment(detailedBolusInfo, true);

                // we subtract insulin, exact amount will be visible with next remainingInsulin update.
                medLinkPumpStatus.reservoirRemainingUnits -= detailedBolusInfo.insulin
                preferences.inc(if (detailedBolusInfo.bolusType == BS.Type.SMB) MedtronicLongNonKey.SmbBoluses else MedtronicLongNonKey.StandardBoluses)

                // calculate time for bolus and set driver to busy for that time
                val bolusTime = (detailedBolusInfo.insulin * 42.0).toInt()
                val time = now + bolusTime * 1000
                busyTimestamps.add(time)
                setEnableCustomAction(MedtronicCustomActionType.ClearBolusBlock, true)
                pumpEnactResultProvider.get().success(true) //
                    .enacted(true) //
                    .bolusDelivered(detailedBolusInfo.insulin) //
                // .carbsDelivered(detailedBolusInfo.carbs)
            } else {
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus not delivered")
                pumpEnactResultProvider.get() //
                    .success(bolusDeliveryType == BolusDeliveryType.CancelDelivery) //
                    .enacted(false) //
                    .comment(rh.gs(R.string.medtronic_cmd_bolus_could_not_be_delivered))
            }
        } finally {
            finishAction("Bolus")
            bolusDeliveryType = BolusDeliveryType.Idle
        }

        // LOG.debug("MedtronicPumpPlugin::deliverBolus - End wait period. Start delivery");
    }

    private fun buildStartStopCommands(smbBolus: Boolean = false): MutableList<CommandStructure<PumpDriverState, BleCommand>> {
        aapsLogger.info(LTag.DATABASE, "buildStartStopCommands")
        aapsLogger.info(LTag.DATABASE, currentPumpStatus.toString())
        if (temporaryBasal != null) {
            aapsLogger.info(LTag.DATABASE, temporaryBasal!!.toStringFull(dateUtil, rh))
        }
        val commands: MutableList<CommandStructure<PumpDriverState, BleCommand>> = ArrayList()
        val callback: Function1<PumpEnactResult, *> = { o: Any? -> null }
        val oper = tempBasalMicrobolusOperations.firstOrNull() // B12: one read, null-safe

        if (medLinkPumpStatus.pumpRunningState.isSuspended() || currentPumpStatus.isSuspended() || !tempBasalMicrobolusOperations.shouldBeSuspended()) {
            commands.addAll(
                buildChangeStatusFunction(
                    oper,
                    callback, ChangeStatusCallback.OperationType.START
                )

            )
        }
        if (!smbBolus && tempBasalMicrobolusOperations.shouldBeSuspended() && temporaryBasal != null && temporaryBasal?.rate!! < 100) {
            commands.addAll(
                buildChangeStatusFunction(
                    oper,
                    callback, ChangeStatusCallback.OperationType.STOP
                )
            )
        }

        return commands
    }

    private val currentPumpStatus: PumpRunningState
        get() {
            // B12: one read of the first step and of the TBR (was isEmpty() + first + temporaryBasal!!: crash when
            // another thread took the last step in between)
            val firstOperation = tempBasalMicrobolusOperations.firstOrNull() ?: return PumpRunningState.Running
            if (firstOperation.operationType == TempBasalMicroBolusPair.OperationType.BOLUS ||
                (firstOperation.operationType == TempBasalMicroBolusPair.OperationType.SUSPEND && !this.medLinkPumpStatus.pumpRunningState.isSuspended())
            ) {
                return PumpRunningState.Running
            }
            val rate = temporaryBasal?.rate ?: return PumpRunningState.Running
            return if (rate >= 100.0) PumpRunningState.Running else PumpRunningState.TempBasalSuspended
        }

    private fun processDeliveredBolus(answer: BolusAnswer) {
        handleNewTreatmentData(of(bolusToJson(answer.detailedBolusInfo)))
        // pumpSyncStorage.addBolusWithTempId(detailedBolusInfo, true, this)
        lastDetailedBolusInfo = null
        handleBolusDelivered(answer.detailedBolusInfo)
    }

    //     private fun bolusInProgress(detailedBolusInfo: DetailedBolusInfo, actionTime: Long) {
    //         aapsLogger.info(LTag.EVENTS, "bolus in progress")
    //         val t = Treatment()
    //         t.isSMB = detailedBolusInfo.bolusType == BS.Type.SMB
    //         t.isTBR = detailedBolusInfo.bolusType == BS.Type.TBR
    //         val bolusEvent = EventOverviewBolusProgress
    //         bolusEvent.t = t
    //         bolusEvent.status = rh.gs(R.string.bolusdelivering, 0.0, detailedBolusInfo.insulin)
    //         bolusEvent.percent = 0
    //         rxBus.send(bolusEvent)
    //
    // //        Function<Supplier<Stream<String>>, MedLinkStandardReturn> function = new BolusHistoryCallback();
    // //        MedLinkPumpMessage msg = new BolusStatusMedLinkMessage(
    // //                MedLinkCommandType.StopStartPump,function, medLinkServiceData,
    // //                getAapsLogger(), bolusingEvent);
    // //        medLinkService.getMedtronicUIComm().executeCommandCP(msg);
    //         val bolusTime = (detailedBolusInfo.insulin * 42.0).toInt()
    //         val bolusEndTime = actionTime + bolusTime * 1000L
    //         val bolusDelta = System.currentTimeMillis() - actionTime
    //         //        Thread bolusEventThread = new Thread() {
    // //            @Override public void run() {
    // //                super.run();
    //         while (bolusEndTime > System.currentTimeMillis() + 100) {
    //             val remaining = (bolusEndTime - System.currentTimeMillis()) / bolusDelta
    //             val bolusEvt = EventOverviewBolusProgress
    //             bolusEvt.t = t
    //             bolusEvt.percent = remaining.toInt()
    //             bolusEvt.status = rh.gs(R.string.medlink_delivered, remaining * detailedBolusInfo.insulin, detailedBolusInfo.insulin)
    //             rxBus.send(bolusEvt)
    //         }
    //         val bolusEvt = EventOverviewBolusProgress
    //         bolusEvt.percent = 100
    //         bolusEvt.status = rh.gs(R.string.medlink_delivered, detailedBolusInfo.insulin, detailedBolusInfo.insulin)
    //         rxBus.send(bolusEvt)
    //         SystemClock.sleep(200)
    //         scheduleNextRefresh(MedLinkMedtronicStatusRefreshType.PumpHistory, -5)
    //         //                getRxBus().send(new EventDismissBolusProgressIfRunning(new PumpEnactResult(getInjector()).
    // //                        success(true).enacted(true).bolusDelivered(detailedBolusInfo.insulin) //
    // //                        .carbsDelivered(detailedBolusInfo.carbs)));
    // //                getRxBus().send(new ());
    // //                getRxBus().send(new EventDismissBolusProgressIfRunning());
    // //            }
    // //        };
    // //        bolusEventThread.start();
    //     }

    /** T16: manual boluses waiting for the user's answer about a pending SMB (new SMBs are refused meanwhile). */
    private val manualAwaitingConfirmation = java.util.concurrent.atomic.AtomicInteger(0)

    override fun deliverBolus(
        detailedBolusInfo: DetailedBolusInfo,
        func: (PumpEnactResult) -> Unit,
    ) {
        val ble = medLinkMedtronicService?.medLinkBLE
        val manual = detailedBolusInfo.bolusType != BS.Type.TBR && detailedBolusInfo.bolusType != BS.Type.SMB
        // T16: an SMB computed without a pending manual bolus in IOB is not delivered; the loop asks again
        if (detailedBolusInfo.bolusType == BS.Type.SMB && (ble?.hasPendingManualBolus() == true || manualAwaitingConfirmation.get() > 0)) {
            aapsLogger.info(LTag.PUMP, "deliverBolus refused: SMB ${detailedBolusInfo.insulin}U while a manual bolus is pending")
            func.invoke(pumpEnactResultProvider.get().success(false).enacted(false).comment("manual bolus pending"))
            return
        }
        // T16: an SMB is pending: the user decides; OK merges it into the manual bolus (or waits for it), cancel cancels the manual bolus
        val pendingSmb = if (manual) ble?.pendingSmbAmount() else null
        if (pendingSmb != null) {
            aapsLogger.info(LTag.PUMP, "manual bolus ${detailedBolusInfo.insulin}U: SMB ${pendingSmb}U pending, asking the user")
            manualAwaitingConfirmation.incrementAndGet()
            MedLinkBolusConfirmActivity.askAmount(
                context,
                rh.gs(R.string.medlink_smb_pending_title),
                rh.gs(R.string.medlink_smb_pending_message, pendingSmb, detailedBolusInfo.insulin),
                detailedBolusInfo.insulin,
                pumpDescription.bolusStep
            ) { amount ->
                aapsLogger.info(LTag.PUMP, "manual bolus ${detailedBolusInfo.insulin}U with pending SMB: user answered amount=$amount")
                try {
                    if (amount != null) {
                        // the user may lower the manual bolus (e.g. by the SMB amount); never above the requested one
                        val info = if (amount < detailedBolusInfo.insulin) detailedBolusInfo.copy().also { it.insulin = amount } else detailedBolusInfo
                        deliverBolus(info, func, smbConsent = true)
                    } else func.invoke(
                        pumpEnactResultProvider.get().success(false).enacted(false).bolusDelivered(0.0)
                            .comment(rh.gs(R.string.medlink_smb_pending_cancelled))
                    )
                } finally {
                    // after deliverBolus: the manual bolus is queued now, hasPendingManualBolus() takes over
                    manualAwaitingConfirmation.decrementAndGet()
                }
            }
            return
        }
        deliverBolus(detailedBolusInfo, func, smbConsent = false)
    }

    private fun deliverBolus(
        detailedBolusInfo: DetailedBolusInfo,
        func: (PumpEnactResult) -> Unit,
        smbConsent: Boolean,
    ) {
        val bolus = detailedBolusInfo.copy()
        aapsLogger.info(
            LTag.PUMP,
            "MedtronicPumpPlugin::deliverBolus - " + BolusDeliveryType.DeliveryPrepared
        )
        aapsLogger.info(LTag.PUMP, bolusToJson(detailedBolusInfo).toString())

        // T6: one bolus in flight on the pump, M resolves it.
        // 4i: a new bolus is refused only when a bolus of the same type is pending; any other type is merged
        // into the pending one (the pump has not received it) or waits for M and runs right after it.
        val ble = medLinkMedtronicService?.medLinkBLE
        val newType = when (bolus.bolusType) {
            BS.Type.TBR -> MedLinkCommandType.TBRBolus
            BS.Type.SMB -> MedLinkCommandType.SMBBolus
            else        -> MedLinkCommandType.Bolus
        }
        val pendingTypes = ble?.pendingBolusTypes().orEmpty()
        if (MedLinkManualBolusRule.refuses(pendingTypes, newType)) {
            aapsLogger.info(LTag.PUMP, "deliverBolus refused: a $newType is pending (pending $pendingTypes, tracker ${ble?.bolusTracker?.current})")
            func.invoke(
                pumpEnactResultProvider.get().success(false).enacted(false)
                    .comment("a bolus of the same type is pending")
            )
            return
        }
        if (bolus.bolusType == BS.Type.SMB) {
            lastSMBBolus = System.currentTimeMillis()
        }
        // the history read gives this bolus as NORMAL: keep its type for it
        rememberSentBolus(bolus.bolusType, bolus.insulin)
        setRefreshButtonEnabled(false)
        if (detailedBolusInfo.insulin > medLinkPumpStatus.reservoirRemainingUnits) {
            func.invoke(
                pumpEnactResultProvider.get() //
                    .success(false) //
                    .enacted(false) //
                    .comment(
                        rh.gs(
                            R.string.medtronic_cmd_bolus_could_not_be_delivered_no_insulin,
                            medLinkPumpStatus.reservoirRemainingUnits,
                            bolus.insulin
                        )
                    )
            )
            return
        }
        bolusDeliveryType = BolusDeliveryType.DeliveryPrepared
        if (isPumpNotReachable) {
            aapsLogger.info(LTag.PUMP, "MedtronicPumpPlugin::deliverBolus - Pump Unreachable.")
            func.invoke(setNotReachable(true, false))
        }
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        if (bolusDeliveryType == BolusDeliveryType.CancelDelivery) {
            // LOG.debug("MedtronicPumpPlugin::deliverBolus - Delivery Canceled.");
            func.invoke(setNotReachable(true, true))
        }

        try {
            bolusDeliveryType = BolusDeliveryType.Delivering

            val resultFunc = { f: MedLinkStandardReturn<BolusAnswer> ->

                func.invoke(
                    pumpEnactResultProvider.get() //
                        .success(bolusDeliveryType == BolusDeliveryType.CancelDelivery) //
                        .enacted(false) //
                        .comment(rh.gs(R.string.medtronic_cmd_bolus_could_not_be_delivered))
                )

                if (detailedBolusInfo.bolusType != BS.Type.TBR && detailedBolusInfo.bolusType != BS.Type.SMB) {
                    Thread {
                        SystemClock.sleep(2000)
                        uiInteraction.runAlarm(
                            f.answer.get().collect(Collectors.joining()),
                            rh.gs(R.string.medtronic_cmd_bolus_could_not_be_delivered),
                            app.aaps.core.ui.R.raw.boluserror
                        )
                    }.start()
                }

            }
            val bolusCallback = BolusCallback(aapsLogger, bolus)
            val andThen: Function<Supplier<Stream<String>>, MedLinkStandardReturn<String>> =
                bolusCallback.andThen { f: MedLinkStandardReturn<BolusAnswer> ->
                    // the bolus answer is decided by MedLinkBLE / MedLinkBolusTracker (onBolusAnswer): it records the
                    // bolus with the pump time and reports the result once through onQueueResult. The old
                    // decision here reported the previous bolus ("Last bolus: 2.0u ... 13:22") as delivered.
                    aapsLogger.info(LTag.PUMPBTCOMM, "bolus answer ${f.functionResult.response.name}: decided by the tracker")
                    if (f.functionResult.answer != null) {
                        MedLinkStandardReturn(f.answer, f.functionResult.answer!!)
                    } else {
                        MedLinkStandardReturn(
                            f.answer,
                            ""
                        ) //TODO check why is happening a nullpointer here
                    }
                }
            if ((currentPumpStatus.isSuspended()) &&
                bolus.insulin > 0.0
            ) {
                aapsLogger.info(LTag.EVENTS, "bolus starting pump")
                startPump(object : Callback() {
                    override fun run() {
                        aapsLogger.info(LTag.EVENTS, "starting pump for bolus")
                    }
                }, bolus.bolusType == BS.Type.TBR)
            }
            val bolusCommand: MedLinkCommandType =
                when (bolus.bolusType) {
                    BS.Type.TBR -> MedLinkCommandType.TBRBolus
                    BS.Type.SMB -> MedLinkCommandType.SMBBolus
                    else -> MedLinkCommandType.Bolus
                }
            // no M queued with the bolus: the bolus answer tells the outcome and the tracker reads M only to
            // follow a bolus being delivered (progress dialog of a manual bolus, expected end of a loop bolus)
            val bolusStatusMessage: MedLinkPumpMessage<String, Any>? = null

            val bleBolusCommand = BleBolusCommand(
                aapsLogger,
                medLinkMedtronicService?.medLinkServiceData,
                this,
                detailedBolusInfo, bolusStatusMessage != null
            )
            bleBolusCommand.smbConsent = smbConsent
            // the one result of this bolus (resolved by the tracker, merged, dropped): the first call wins
            val resultSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val manual = bolus.bolusType == BS.Type.NORMAL
            if (manual) manualBolusStopRequested = false
            bleBolusCommand.onQueueResult = { success, delivered ->
                if (resultSent.compareAndSet(false, true)) {
                    aapsLogger.info(LTag.PUMP, "${bolus.bolusType} bolus ${bolus.insulin}U result success=$success delivered=$delivered")
                    if (success) bolusDeliveryType = BolusDeliveryType.Idle
                    if (manual && manualBolusStopRequested) {
                        // B8: the dialog was closed at Stop; tell what the pump really delivered
                        manualBolusStopRequested = false
                        val text = if (success) rh.gs(
                            R.string.medlink_bolus_stopped_partial,
                            rh.gs(app.aaps.core.ui.R.string.format_insulin_units, delivered),
                            rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolus.insulin)
                        ) else rh.gs(R.string.medlink_bolus_stopped_unknown)
                        aapsLogger.info(LTag.PUMP, "bolus stop: result told to the user: $text")
                        notificationManager.post(NotificationId.MEDLINK_BOLUS_STOPPED, text, validMinutes = 60)
                    } else if (manual && success) {
                        // the dialog is closed by MedLinkCommandBolus (completeAndAutoClear / clear) with this result
                        bolusProgressData.updateProgress(percent = 100)
                    }
                    func.invoke(
                        pumpEnactResultProvider.get().success(success).enacted(success).bolusDelivered(delivered)
                            .also { if (!success) it.comment(rh.gs(R.string.medtronic_cmd_bolus_could_not_be_delivered)) }
                    )
                } else aapsLogger.info(LTag.PUMP, "${bolus.bolusType} bolus ${bolus.insulin}U result already sent, ignoring success=$success delivered=$delivered")
            }
            // progress dialog of a manual bolus (a merged TBR/SMB is part of the total: show the manual share)
            if (manual) bleBolusCommand.onProgress = { delivered, requested ->
                val fraction = if (requested > 0.0) (delivered / requested).coerceIn(0.0, 1.0) else 0.0
                bolusProgressData.updateProgress(PumpInsulin(bolus.insulin * fraction))
            }
            // 4p: until the pump delivers, tell the user what the bolus waits for (moving bar)
            if (manual) {
                bleBolusCommand.onPhase = { phase, attempt, maxAttempts ->
                    val text = when (phase) {
                        MedLinkBolusPhase.CONNECTING          -> rh.gs(R.string.medlink_bolus_phase_connecting)
                        MedLinkBolusPhase.READING_STATUS      -> rh.gs(R.string.medlink_bolus_phase_reading_status)
                        MedLinkBolusPhase.SENDING             ->
                            if (attempt > 1) rh.gs(R.string.medlink_bolus_phase_sending_try, attempt, maxAttempts)
                            else rh.gs(R.string.medlink_bolus_phase_sending)
                        MedLinkBolusPhase.CONFIRMED           -> rh.gs(R.string.medlink_bolus_phase_confirmed)
                        MedLinkBolusPhase.WAITING_OTHER_BOLUS -> rh.gs(R.string.medlink_bolus_phase_waiting_other)
                    }
                    bolusProgressData.updateWaiting(text)
                }
                bolusProgressData.updateWaiting(rh.gs(R.string.medlink_bolus_phase_connecting))
            }
            val msg = BolusMedLinkMessage(
                bolusCommand, bolus,
                andThen, bolusStatusMessage,
                btSleepTime,
                bleBolusCommand,
                tempBasalMicrobolusOperations.shouldBeSuspended()
            )
            val startStopMsg = buildStartStopCommands(detailedBolusInfo.bolusType == BS.Type.SMB)
            if (startStopMsg.isNotEmpty()) {
                medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(
                    MedLinkPumpMessage<PumpDriverState, Any>(
                        startStopMsg,
                        btSleepTime
                    ), msg
                )
            } else {
                medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)
            }
            // a TBR bolus due soon joins this SMB (one pump bolus)
            if (bolus.bolusType == BS.Type.SMB) joinDueTbrBolusStep()
            setRefreshButtonEnabled(true)

            //            int count = 0;
            //            while ((!isPumpNotReachable() || bolusTimesstamp == 0l) && count < 15) {
            //                SystemClock.sleep(5000);
            //                count++;
            //            }
            // LOG.debug("MedtronicPumpPlugin::deliverBolus - Response: {}", response);
            if (!isPumpNotReachable) {
                if (bolusDeliveryType == BolusDeliveryType.CancelDelivery) {
                    // LOG.debug("MedtronicPumpPlugin::deliverBolus - Delivery Canceled after Bolus started.");
                    Thread {
                        SystemClock.sleep(2000)
                        uiInteraction.runAlarm(
                            rh.gs(R.string.medtronic_cmd_cancel_bolus_not_supported),
                            rh.gs(R.string.medtronic_warning),
                            app.aaps.core.ui.R.raw.boluserror
                        )
                    }.start()

                }
                val now = System.currentTimeMillis()

                aapsLogger.info(LTag.PUMP,"delivery at the latest set")

                aapsLogger.info(LTag.PUMP, Date(now).toString())
                bolus.deliverAtTheLatest = now
                // detailedBolusInfo.deliverAt = now // not sure about that one

                //                activePlugin.getActiveTreatments().addToHistoryTreatment(detailedBolusInfo, true);

                // we subtract insulin, exact amount will be visible with next remainingInsulin update.
                medLinkPumpStatus.reservoirRemainingUnits -= bolus.insulin
                preferences.inc(if (bolus.bolusType == BS.Type.SMB) MedtronicLongNonKey.SmbBoluses else MedtronicLongNonKey.StandardBoluses)

                // calculate time for bolus and set driver to busy for that time
                val bolusTime = (bolus.insulin * 42.0).toInt()
                val time = now + bolusTime * 1000L
                busyTimestamps.add(time)
                //                setEnableCustomAction(MedtronicCustomActionType.ClearBolusBlock, true);
            }
        } finally {
            finishAction("Bolus")
            bolusDeliveryType = BolusDeliveryType.Idle
        }
    }

    private fun pumpBlockText(block: MedLinkPumpBlock): String = when (block) {
        MedLinkPumpBlock.PUMP_LOCKED -> rh.gs(R.string.medlink_pump_block_locked, preferences.get(MedLinkMedtronicIntPreferenceKey.CommandAttempts))
    }

    /** BUGs_TASKS T1/T4/T7/T8: lets MedLinkBLE read M, record resolved boluses and alert the user. */
    private fun installBolusSafetyHooks() {
        val ble = medLinkMedtronicService?.medLinkBLE ?: return
        // 4aa: one setting for bolus tries, START/STOP retries and the "alarm on the pump screen" message
        ble.commandAttemptsProvider = { preferences.get(MedLinkMedtronicIntPreferenceKey.CommandAttempts) }
        ble.pumpBlockHandler = { block ->
            aapsLogger.warn(LTag.PUMP, "MedLink pump block: ${block ?: "cleared"}")
            if (block == null) notificationManager.dismiss(NotificationId.PUMP_UNREACHABLE)
            else {
                // commands are not refused while locked: they keep trying and the first one that works clears the lock
                // the alarm sound comes from runAlarm below: the message itself is IMPORTANT (was URGENT without sound)
                notificationManager.post(NotificationId.PUMP_UNREACHABLE, pumpBlockText(block), level = NotificationLevel.IMPORTANT)
                uiInteraction.runAlarm(pumpBlockText(block), rh.gs(R.string.medlink_pump_block_locked_title), app.aaps.core.ui.R.raw.boluserror)
            }
        }
        ble.bolusStatusMessageProvider = {
            BolusStatusMedLinkMessage(
                // plain read: the answer only feeds the MedLinkBolusTracker via BleBolusStatusCommand, no side effects
                object : app.aaps.pump.common.hw.medlink.activities.BaseStringAggregatorCallback() {},
                btSleepTime,
                BleBolusStatusCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData, this)
            )
        }
        ble.bolusRecorder = { amount, pumpTime, info ->
            val record = (info?.copy() ?: DetailedBolusInfo()).also {
                it.insulin = amount
                it.timestamp = pumpTime
                it.deliverAtTheLatest = pumpTime
            }
            handleNewTreatmentData(record, false)
        }
        ble.bolusAlarmHandler = { reason ->
            aapsLogger.warn(LTag.PUMP, "MedLink bolus alarm: $reason")
            uiInteraction.runAlarm(reason, rh.gs(R.string.medlink_bolus_alarm_title), app.aaps.core.ui.R.raw.boluserror)
        }
        ble.pumpConnectionListener = { reached -> onPumpConnectionResult(reached) }
    }

    // ---- T20: alarm when the pump is not reached several times in a row ----

    /** Read from the running mode before each rule call (the rule itself is not suspend). */
    @Volatile private var pumpDisconnectedOnPurpose = false
    private val connectionAlarmLock = Any()
    private val connectionAlarmRule by lazy {
        MedLinkConnectionAlarmRule(
            MedLinkClock { System.currentTimeMillis() },
            {
                MedLinkConnectionAlarmRule.Settings(
                    enabled = preferences.get(MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarm),
                    threshold = preferences.get(MedLinkMedtronicIntPreferenceKey.ConnectionAlarmFailures)
                )
            },
            { pumpDisconnectedOnPurpose }
        )
    }
    private val torchBlinker by lazy { context?.let { MedLinkTorchBlinker(it, aapsLogger, medLinkScope) } }

    /** True while our full screen alarm may be on screen, so [UiInteraction.stopAlarm] never stops another alarm. */
    @Volatile private var connectionAlarmSoundOn = false

    private fun onPumpConnectionResult(reached: Boolean) {
        medLinkScope.launch {
            pumpDisconnectedOnPurpose = try {
                persistenceLayer.getRunningModeActiveAt(dateUtil.now()).mode == RM.Mode.DISCONNECTED_PUMP
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "MedLink: running mode not read for the connection alarm", e)
                false
            }
            val (action, failures, outageStart) = synchronized(connectionAlarmLock) {
                val a = if (reached) connectionAlarmRule.onSuccess() else connectionAlarmRule.onFailure()
                Triple(a, connectionAlarmRule.failureCount, connectionAlarmRule.outageStartTime)
            }
            if (!reached) aapsLogger.info(LTag.PUMP, "MedLink: pump not reached, $failures in a row")
            handleConnectionAlarm(action)
            // T21
            if (reached) endZeroTbr(dateUtil.now()) else checkZeroTbrStart(outageStart, failures)
        }
    }

    private fun handleConnectionAlarm(action: MedLinkConnectionAlarmRule.Action) {
        when (action) {
            MedLinkConnectionAlarmRule.Action.None     -> Unit
            is MedLinkConnectionAlarmRule.Action.Raise -> {
                val text = rh.gs(R.string.medlink_pump_unreachable_message, action.failures, dateUtil.timeString(action.outageStart))
                aapsLogger.warn(LTag.PUMP, "MedLink: connection alarm raised, ${action.failures} failures since ${action.outageStart}")
                notificationManager.post(
                    NotificationId.MEDLINK_PUMP_UNREACHABLE, text,
                    actions = listOf(
                        NotificationAction(app.aaps.core.ui.R.string.ok) { acknowledgeConnectionAlarm() },
                        NotificationAction(R.string.medlink_silence_15_min) { silenceConnectionAlarm() }
                    )
                )
                if (preferences.get(MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarmSound)) {
                    connectionAlarmSoundOn = true
                    uiInteraction.runAlarm(text, rh.gs(R.string.medlink_pump_unreachable_title), app.aaps.core.ui.R.raw.alarm)
                }
                if (preferences.get(MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarmTorch))
                    torchBlinker?.start() ?: aapsLogger.warn(LTag.PUMP, "MedLink: no context, flashlight skipped")
            }

            MedLinkConnectionAlarmRule.Action.Stop     -> {
                aapsLogger.info(LTag.PUMP, "MedLink: connection alarm stopped (setting off or pump disconnected on purpose)")
                stopConnectionAlarmOutputs("MedLink connection alarm stopped")
                notificationManager.dismiss(NotificationId.MEDLINK_PUMP_UNREACHABLE)
            }

            MedLinkConnectionAlarmRule.Action.Restored -> {
                aapsLogger.info(LTag.PUMP, "MedLink: pump connection restored, connection alarm cleared")
                stopConnectionAlarmOutputs("MedLink pump connection restored")
                notificationManager.dismiss(NotificationId.MEDLINK_PUMP_UNREACHABLE)
                notificationManager.post(NotificationId.MEDLINK_PUMP_CONNECTION_RESTORED, rh.gs(R.string.medlink_pump_connection_restored), validMinutes = 30)
            }
        }
    }

    private fun stopConnectionAlarmOutputs(reason: String) {
        torchBlinker?.stop()
        if (connectionAlarmSoundOn) {
            connectionAlarmSoundOn = false
            uiInteraction.stopAlarm(reason)
        }
    }

    /** "OK": no new alarm until the pump is reached again. */
    private fun acknowledgeConnectionAlarm() {
        synchronized(connectionAlarmLock) { connectionAlarmRule.onAcknowledged() }
        aapsLogger.info(LTag.PUMP, "MedLink: connection alarm OK pressed")
        stopConnectionAlarmOutputs("MedLink connection alarm OK")
        notificationManager.dismiss(NotificationId.MEDLINK_PUMP_UNREACHABLE)
    }

    /** "Silence 15 min": no sound and light; the alarm comes back after 15 min if the pump is still not reached. */
    private fun silenceConnectionAlarm() {
        synchronized(connectionAlarmLock) { connectionAlarmRule.onSilenced() }
        aapsLogger.info(LTag.PUMP, "MedLink: connection alarm silenced for 15 min")
        stopConnectionAlarmOutputs("MedLink connection alarm silenced")
        medLinkScope.launch {
            delay(MedLinkConnectionAlarmRule.SILENCE_MS)
            handleConnectionAlarm(synchronized(connectionAlarmLock) { connectionAlarmRule.onTick() })
        }
    }

    // ---- T21: 0 % TBR in AAPS when the pump battery is old (red) and the pump is not reached ----

    /**
     * True from the start of the 0 % record until the user answers: no SMB and no TBR above 100 % (TBR up to 100 % and
     * suspends are allowed). With "No" the IOB would be too low, so no extra insulin is planned before the answer.
     */
    private fun zeroTbrLimitActive(): Boolean = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrTempId) != 0L

    /** The question was posted in this app session (it is posted again after an app restart). */
    @Volatile private var zeroTbrQuestionPosted = false

    private suspend fun checkZeroTbrStart(outageStart: Long, failures: Int) {
        if (outageStart == 0L || zeroTbrLimitActive()) return
        val lastChange = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.PUMP_BATTERY_CHANGE)
        val ageHours = lastChange?.let { (dateUtil.now() - it.timestamp) / HOUR_MS }
        val start = MedLinkZeroTbrRule.shouldStart(
            batteryInfoIsAge = isBatteryAge(),
            batteryAgeHours = ageHours,
            criticalHours = preferences.get(IntKey.OverviewBageCritical),
            failures = failures,
            threshold = preferences.get(MedLinkMedtronicIntPreferenceKey.ConnectionAlarmFailures),
            alreadyStarted = false,
            alarmEnabled = preferences.get(MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarm),
            disconnectedOnPurpose = pumpDisconnectedOnPurpose
        )
        if (!start) return
        val tempId = generateTempId(outageStart)
        // only a record in AAPS: the pump cannot be reached. It ends when the pump answers again (endZeroTbr).
        val stored = pumpSync.addTemporaryBasalWithTempId(
            outageStart, PumpRate(0.0), ZERO_TBR_MAX_DURATION_MS, true, tempId,
            PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND, model(), serialNumber()
        )
        if (!stored) {
            aapsLogger.error(LTag.PUMP, "MedLink: the 0 % basal for the pump not reached (battery ${ageHours} h old) could not be stored")
            return
        }
        val reservoir = medLinkPumpStatus.reservoirRemainingUnits
        preferences.put(MedtronicLongNonKey.MedLinkZeroTbrTempId, tempId)
        preferences.put(MedtronicLongNonKey.MedLinkZeroTbrStart, outageStart)
        preferences.put(MedtronicLongNonKey.MedLinkZeroTbrEnd, 0L)
        preferences.put(MedtronicLongNonKey.MedLinkZeroTbrReservoirBefore, if (reservoir > 0.0) (reservoir * 1000).roundToLong() else -1L)
        aapsLogger.warn(LTag.PUMP, "MedLink: pump not reached $failures times and battery $ageHours h old: 0 % basal stored from $outageStart (temp id $tempId), reservoir $reservoir U")
    }

    /** The pump answers again: the 0 % record ends now, and the question waits for the next status (fresh reservoir). */
    private suspend fun endZeroTbr(now: Long) {
        val tempId = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrTempId)
        if (tempId == 0L || preferences.get(MedtronicLongNonKey.MedLinkZeroTbrEnd) != 0L) return
        val start = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrStart)
        val ended = pumpSync.syncTemporaryBasalWithTempId(
            start, PumpRate(0.0), (now - start).coerceAtLeast(0L), true, tempId,
            PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND, null, model(), serialNumber()
        )
        preferences.put(MedtronicLongNonKey.MedLinkZeroTbrEnd, now)
        zeroTbrQuestionPosted = false
        aapsLogger.warn(LTag.PUMP, "MedLink: pump reached again, 0 % basal from $start ended at $now (stored=$ended); asking the user")
    }

    /** Called after each status read: posts the question (again after an app restart) until the user answers. */
    private fun postZeroTbrQuestionIfWaiting() {
        val tempId = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrTempId)
        val end = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrEnd)
        if (tempId == 0L || end == 0L || zeroTbrQuestionPosted) return
        zeroTbrQuestionPosted = true
        medLinkScope.launch {
            try {
                val start = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrStart)
                val scheduled = scheduledBasalUnits(start, end)
                val before = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrReservoirBefore).takeIf { it >= 0 }?.let { it / 1000.0 }
                val now = medLinkPumpStatus.reservoirRemainingUnits.takeIf { it > 0.0 }
                val unknown = rh.gs(R.string.medlink_value_unknown)
                val units = { v: Double? -> v?.let { rh.gs(app.aaps.core.ui.R.string.format_insulin_units, it) } ?: unknown }
                val hint = when (MedLinkZeroTbrRule.pumpRan(before, now, scheduled)) {
                    MedLinkZeroTbrRule.PumpRan.PROBABLY_NO  -> rh.gs(R.string.medlink_zero_tbr_hint_stopped, units(before), units(now))
                    MedLinkZeroTbrRule.PumpRan.PROBABLY_YES -> rh.gs(R.string.medlink_zero_tbr_hint_ran, units(before), units(now), units(scheduled))
                    MedLinkZeroTbrRule.PumpRan.UNKNOWN      -> rh.gs(R.string.medlink_zero_tbr_hint_unknown, units(before), units(now), units(scheduled))
                }
                postZeroTbrQuestion(rh.gs(R.string.medlink_zero_tbr_question, dateUtil.timeString(start), dateUtil.timeString(end), hint))
                aapsLogger.info(LTag.PUMP, "MedLink: 0 % basal question posted (reservoir before $before, now $now, scheduled $scheduled U)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                zeroTbrQuestionPosted = false
                aapsLogger.error(LTag.PUMP, "MedLink: 0 % basal question not posted", e)
            }
        }
    }

    private fun postZeroTbrQuestion(text: String) {
        notificationManager.post(
            NotificationId.MEDLINK_ZERO_TBR_QUESTION, text,
            actions = listOf(
                NotificationAction(R.string.medlink_zero_tbr_keep) { answerZeroTbr(keep = true) },
                NotificationAction(R.string.medlink_zero_tbr_remove) { answerZeroTbr(keep = false) }
            )
        )
    }

    /** Scheduled basal (AAPS profile) of the outage, in U, for the hint of the question. */
    private suspend fun scheduledBasalUnits(start: Long, end: Long): Double {
        var units = 0.0
        var t = start
        while (t < end) {
            val profile = profileFunction.get().getProfile(t) ?: return units
            units += profile.getBasal(t) * minOf(SCHEDULED_BASAL_STEP_MS, end - t) / HOUR_MS.toDouble()
            t += SCHEDULED_BASAL_STEP_MS
        }
        return units
    }

    /**
     * "Yes": the pump stopped, the record stays. "No": the pump ran, the record is removed (invalid), so the IOB counts
     * the scheduled basal again. Then the limit ends and the loop runs again at once with fresh data (no old dose is
     * applied, user 2026-11-14).
     */
    private fun answerZeroTbr(keep: Boolean) {
        medLinkScope.launch {
            val tempId = preferences.get(MedtronicLongNonKey.MedLinkZeroTbrTempId)
            if (tempId == 0L) return@launch
            if (!keep && !pumpSync.invalidateTemporaryBasalWithTempId(tempId)) {
                // never end the limit with a wrong (too low) IOB: ask again
                aapsLogger.error(LTag.PUMP, "MedLink: the 0 % basal (temp id $tempId) could not be removed, the question stays")
                postZeroTbrQuestion(rh.gs(R.string.medlink_zero_tbr_remove_failed))
                return@launch
            }
            aapsLogger.info(LTag.PUMP, "MedLink: user answered about the 0 % basal: ${if (keep) "keep (pump stopped)" else "removed (pump ran)"}")
            preferences.put(MedtronicLongNonKey.MedLinkZeroTbrTempId, 0L)
            preferences.put(MedtronicLongNonKey.MedLinkZeroTbrStart, 0L)
            preferences.put(MedtronicLongNonKey.MedLinkZeroTbrEnd, 0L)
            preferences.put(MedtronicLongNonKey.MedLinkZeroTbrReservoirBefore, -1L)
            zeroTbrQuestionPosted = false
            notificationManager.dismiss(NotificationId.MEDLINK_ZERO_TBR_QUESTION)
            loop.get().invoke("MedLink 0 % basal answered", allowNotification = true)
        }
    }

    private fun readBolusData(detailedBolusInfo: DetailedBolusInfo) {
        aapsLogger.info(LTag.PUMPBTCOMM, "get full bolus data")
        lastBolusHistoryRead = System.currentTimeMillis()
        val status = MedLinkPartialBolus()
        val func = BolusDeliverCallback(
            status, this, aapsLogger,
            detailedBolusInfo
        )
        assert(medLinkMedtronicService != null)
        val msg = BolusStatusMedLinkMessage(
            func,
            btSleepTime,
            BleBolusStatusCommand(aapsLogger, medLinkMedtronicService?.medLinkServiceData, this)
        )
        medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)
    }

    override fun triggerUIChange() {
        rxBus.send(EventMedtronicPumpValuesChanged())
    }

    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        aapsLogger.warn(LTag.PUMP, logPrefix + "Time or TimeZone changed. ")
        hasTimeDateOrTimeZoneChanged = true
    }

    override fun getPumpInfo(): RileyLinkPumpInfo? {
        val frequency =
            rh.gs(if (medLinkPumpStatus.pumpFrequency == "medtronic_pump_frequency_us_ca") R.string.medtronic_pump_frequency_us_ca else R.string.medtronic_pump_frequency_worldwide)
        val model =
            if (!medtronicUtil.isModelSet) "???" else "Medtronic " + medLinkPumpStatus.medtronicDeviceType!!.pumpModel
        val serialNumber = medLinkPumpStatus.serialNumber
        return RileyLinkPumpInfo(frequency, model, serialNumber)
    }

//     override fun getLastConnectionTimeMillis(): Long {
//         return pumpStatusData.lastConnection
//     }

    // setLastCommunicationToNow sets lastConnection and lastDataTime together
    override val lastDataTime: StateFlow<Long> by lazy { medLinkPumpStatus.lastConnectionFlow }

    override fun setLastCommunicationToNow() {
        medLinkPumpStatus.setLastCommunicationToNow()
    }


    fun resetStatusState() {
        firstRun = true
        isRefresh = true
    }

    // private fun buildIntentSensValues(vararg bgs: InMemoryGlucoseValue): Intent {
    //     val intent = Intent()
    //     intent.putExtra("sensorType", "Enlite")
    //     val glucoseValues = Bundle()
    //     val fingerValues = Bundle()
    //     var gvPosition = 0
    //     var meterPosition = 0
    //     for (bg in bgs) {
    //         if (bg ==GlucoseValue.SourceSensor.UNKNOW) {
    //             aapsLogger.info(LTag.BGSOURCE, "User bg source")
    //             val bgBundle = Bundle()
    //             bgBundle.putDouble("meterValue", bg.value)
    //             bgBundle.putLong("timestamp", bg.timestamp)
    //             fingerValues.putBundle("" + meterPosition, bgBundle)
    //             meterPosition++
    //         } else {
    //             val bgBundle = Bundle()
    //             bgBundle.putDouble("value", bg.value)
    //             bgBundle.putLong("date", bg.timestamp)
    //             bgBundle.putString("direction", bg.get)
    //             //            bgBundle.putString("raw", bg.raw);
    //             glucoseValues.putBundle("" + gvPosition, bgBundle)
    //             gvPosition++
    //         }
    //     }
    //     intent.putExtra("glucoseValues", glucoseValues)
    //     intent.putExtra("meters", fingerValues)
    //     intent.putExtra("isigValues", Bundle())
    //     return intent
    // }

    private fun buildIntentSensValues(vararg sensorDataReadings: BgSync.BgHistory): Bundle {
        val baseBundle = Bundle()
        baseBundle.putString("sensorType", "Enlite")
        // val glucoseValues = Bundle()
        val fingerValues = Bundle()
        val isigValues = Bundle()
        // var gvPosition = 0
        var meterPosition = 0
        var isigPosition = 0
        sensorDataReadings.forEach { value ->

            aapsLogger.info(LTag.BGSOURCE, "User bg source")
            value.bgCalibration.forEach {
                val bgBundle = Bundle()
                bgBundle.putDouble("meterValue", it.value)
                bgBundle.putLong("timestamp", it.timestamp)
                fingerValues.putBundle("" + meterPosition, bgBundle)
                meterPosition++
            }
            value.bgValue.forEach {
                //     val bgBundle = Bundle()
                //     bgBundle.putDouble("value", it.value)
                //     bgBundle.putLong("date", it.timestamp)
                //     // bgBundle.putString("direction", sens.trendArrow.text)
                //     //                bgBundle.putString("raw", bg.raw);
                //     glucoseValues.putBundle("" + gvPosition, bgBundle)
                //     gvPosition++
                // }
                val sensBundle = Bundle()
                sensBundle.putDouble("value", it.value)
                sensBundle.putLong("timestamp", it.timestamp)
                // sensBundle.putString("direction", sens.trendArrow.text)
                it.calibrationFactor?.let { it1 -> sensBundle.putDouble("calibration_factor", it1) }
                it.sensorUptime?.let { it1 -> sensBundle.putInt("sensor_uptime", it1) }
                it.isig?.let { it1 -> sensBundle.putDouble("isig", it1) }
                // sensBundle.putDouble("delta", it.de)
                isigValues.putBundle("" + isigPosition, sensBundle)
                aapsLogger.info(LTag.BGSOURCE, sensBundle.toString())
                isigPosition++
            }
        }
        baseBundle.putBundle("glucoseValues", isigValues)
        baseBundle.putBundle("meters", fingerValues)
        // intent.putExtra("isigValues", isigValues)
        return baseBundle
    }

    //    private Bundle buildIntentSensValues(SensorDataReading... sens) {
    //        Bundle isigValues = new Bundle();
    //
    //        int gvPosition = 0;
    //        int meterPosition = 0;
    //        for (SensorDataReading bg : sens) {
    //            Bundle bgBundle = new Bundle();
    //            bgBundle.putDouble("value", bg.value);
    //            bgBundle.putLong("date", bg.date);
    //            bgBundle.putString("direction", bg.direction);
    //            bgBundle.putDouble("calibrationFactor", bg.calibrationFactor);
    //            bgBundle.putInt("sensorUptime", bg.sensorUptime);
    //            bgBundle.putDouble("isig", bg.isig);
    //            bgBundle.putDouble("delta", bg.deltaSinceLastBG);
    //            isigValues.putBundle("" + gvPosition, bgBundle);
    //            gvPosition++;
    //        }
    //        return isigValues;
    //    }
    fun handleNewCarePortalEvent(events: Supplier<Stream<JSONObject>>) {
        events.get().forEach { e: JSONObject ->
            runBlocking { pumpSync.insertTherapyEventIfNewWithTimestamp(
                e!!.getLong("mills"),
                TE.Type.valueOf(e.getString("eventType")),
                pumpSerial = medLinkServiceData.pumpIDs ?: "000000",
                pumpType = pumpType
            ) }
            if (TE.Type.valueOf(e.getString("eventType")) == TE.Type.PUMP_BATTERY_CHANGE) {
                medLinkPumpStatus.batteryAge = e.getLong("mills")
            }
        }
    }

    fun bolusFromJson(bolusJson: JSONObject): DetailedBolusInfo {
        return Gson().fromJson(bolusJson.toString(), DetailedBolusInfo::class.java)
    }

    fun bolusToJson(bolusInfo: DetailedBolusInfo): JSONObject {
        return JSONObject(Gson().toJson(bolusInfo))
    }

    override fun handleNewTreatmentData(bolusInfo: DetailedBolusInfo, history: Boolean) {
        this.handleNewTreatmentData(of(bolusToJson(bolusInfo)), history)
    }

    override fun handleNewTreatmentData(bolusInfo: Stream<JSONObject>, history: Boolean) {
        bolusHistoryCheck = false
        var lastProcessedBolusTimestamp = 0L
        var minBolusTimestamp = 0L
        val state = runBlocking { pumpSync.expectedPumpState() }
        var count = 0
        var lastHistoryBolusTimestamp = 0L
        bolusInfo.forEachOrdered { bolusJson: JSONObject ->
            count += 1

            val bInfo = bolusFromJson(bolusJson)

            bInfo.pumpType = this.pumpType
            bInfo.bolusPumpId = this.medLinkServiceData.pumpIDs?.toLong()
            aapsLogger.info(LTag.PUMPBTCOMM, lastDetailedBolusInfo?.toString() ?: "")
            aapsLogger.info(LTag.PUMPBTCOMM, bInfo.toString())
            if (minBolusTimestamp < (bInfo.bolusTimestamp ?: bInfo.timestamp)) {
                minBolusTimestamp = bInfo.bolusTimestamp ?: bInfo.timestamp
            }
            // the pump history gives every bolus as NORMAL: a bolus this app sent keeps its type (SMB / TBR);
            // a joined bolus (SMB + TBR in one pump bolus) is stored as its parts, the same as the bolus tracker does
            var joinedParts: List<MedLinkSentBolusKinds.Sent>? = null
            if (bInfo.bolusType == BS.Type.NORMAL) {
                val historyTime = bInfo.bolusTimestamp ?: bInfo.timestamp
                val sent = sentBolusFor(bInfo.insulin, historyTime)
                if (sent != null) {
                    if (sent.type != BS.Type.NORMAL) {
                        aapsLogger.info(LTag.PUMP, "history bolus ${bInfo.insulin}U: type ${sent.type} (sent by the app at ${dateUtil.timeString(sent.sentAt)})")
                        bInfo.bolusType = sent.type
                    }
                } else {
                    joinedParts = joinedSentBolusFor(bInfo.insulin, historyTime)
                    joinedParts?.let { parts ->
                        aapsLogger.info(LTag.PUMP, "history bolus ${bInfo.insulin}U: joined bolus, stored as ${parts.joinToString { "${it.type} ${it.amount}U" }}")
                    }
                }
            }
            var mod = (bInfo.bolusTimestamp ?: bInfo.timestamp).mod(60000)
            if (lastProcessedBolusTimestamp == 0L && state.bolus != null && state.bolus?.timestamp == bInfo.bolusTimestamp) {
                remainingBolus = bInfo
                readBolusHistory()
            } else if (bInfo.carbs != 0.0) {
                pumpSyncStorage.addBolusWithTempId(bInfo, true, this)
            } else if (bInfo.insulin > 0) {
                if (mod != 0 && lastProcessedBolusTimestamp == (bInfo.bolusTimestamp?.minus(mod)
                        ?: bInfo.timestamp.minus(mod))
                ) {
                    mod -= 20000
                    if (::remainingBolus.isInitialized && remainingBolus.bolusTimestamp == bInfo.bolusTimestamp?.minus(
                            mod
                        ) && bInfo.insulin == remainingBolus.insulin
                    ) {
                        bInfo.bolusType = remainingBolus.bolusType
                        remainingBolus = DetailedBolusInfo()
                    }
                }
                if (lastHistoryBolusTimestamp == (bInfo.bolusTimestamp ?: bInfo.timestamp)) {
                    lastHistoryCount += 1

                    bInfo.bolusTimestamp.let {
                        bInfo.bolusTimestamp = it?.plus(lastHistoryCount * 20000)
                    }
                    bInfo.timestamp = bInfo.timestamp + lastHistoryCount * 20000

                } else {
                    lastHistoryCount = 0
                }
                lastHistoryBolusTimestamp = bInfo.bolusTimestamp ?: bInfo.timestamp
                lastProcessedBolusTimestamp =
                    bInfo.bolusTimestamp?.minus(mod) ?: bInfo.timestamp.minus(mod)
                if (lastProcessedBolusTimestamp < System.currentTimeMillis() + 120000) {
                    val parts = joinedParts
                    if (parts == null) {
                        pumpSyncStorage.addMedLinkBolusWithTempId(bInfo, true, this)
                    } else {
                        // part i at pump time + i s: the same records (and temporary ids) as the bolus tracker
                        parts.forEachIndexed { i, part ->
                            val partInfo = bolusFromJson(bolusToJson(bInfo))
                            partInfo.insulin = part.amount
                            partInfo.bolusType = part.type
                            partInfo.bolusTimestamp = bInfo.bolusTimestamp?.let { MedLinkLoopBolusMerge.partTime(it, i) }
                            partInfo.timestamp = MedLinkLoopBolusMerge.partTime(bInfo.timestamp, i)
                            pumpSyncStorage.addMedLinkBolusWithTempId(partInfo, true, this)
                        }
                    }
                }
            }
        }
        if (state.bolus != null && minBolusTimestamp > state.bolus?.timestamp!! && count > 1) {
            aapsLogger.info(LTag.PUMPBTCOMM, "previous read?")
            aapsLogger.info(LTag.PUMPBTCOMM, "" + state.bolus?.timestamp)
            aapsLogger.info(LTag.PUMPBTCOMM, "" + minBolusTimestamp)
            readBolusHistory(System.currentTimeMillis() - lastHistoryBolusTimestamp > 600000)
        }
    }

    override fun getBatteryType(): String {
        return medLinkPumpStatus.batteryType.toString()
    }

    override fun changeStatusTime(currentTimeMillis: Long) {
        changeStatusTimestamp = currentTimeMillis
    }

    private fun handleNewEvent() {
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        aapsLogger.info(LTag.EVENTS, " new event ")
        aapsLogger.info(LTag.EVENTS, "" + isInitialized)
        aapsLogger.info(LTag.EVENTS, "" + (medLinkPumpStatus.lastBolusTime?.time ?: ""))
        aapsLogger.info(LTag.EVENTS, "" + pumpTimeDelta)
        val pumpState = runBlocking { pumpSyncStorage.pumpSync.expectedPumpState() }

        if (isInitialized && medLinkPumpStatus.lastBolusTime != null) {
            //TODO possible removal of this first sentence
            if (pumpState.bolus?.timestamp != medLinkPumpStatus.lastBolusTime?.time
                || preferences.get(MedLinkBooleanPreferenceKey.ForceBolusHistoryReadAfter) || bolusHistoryCheck
            ) {
                aapsLogger.info(LTag.PUMPBTCOMM, "read bolus history")
                readBolusHistory()
            }
            if (lastDetailedBolusInfo != null) {
                aapsLogger.info(LTag.PUMPBTCOMM, "lastdetailedbolusinfo")

                aapsLogger.info(LTag.PUMP,"delivery at the latest set")

                aapsLogger.info(LTag.PUMP, Date(pumpStatusData.lastBolusTime!!.time).toString())
                lastDetailedBolusInfo!!.deliverAtTheLatest = pumpStatusData.lastBolusTime!!.time
                lastDetailedBolusInfo!!.bolusTimestamp = pumpStatusData.lastBolusTime!!.time
                handleNewTreatmentData(of(bolusToJson(lastDetailedBolusInfo!!)))
                lastDetailedBolusInfo = null

            }
        }
    }

    override fun calibrate(calibrationInfo: Double) {

        var calibrationValue = calibrationInfo
        if (glucoseUnit() == BgSync.GlucoseUnit.MMOL) {
            calibrationValue *= 18
        }
        val medLinkCalibrationCallback = MedLinkCalibrationArgCallback(
            aapsLogger,
            this,
            medtronicUtil,
            rh,
            rxBus
        )

        val bleCommand = BleCalibrateCommand(aapsLogger, medLinkServiceData, this)
        val calibrationCallback = MedLinkCalibrationCallback(
            aapsLogger,
            this, medtronicUtil, rh, rxBus
        )
        val msg = CalibrateMedLinkMessage.invoke(
            calibrationValue,
            calibrationCallback,
            medLinkCalibrationCallback,
            btSleepTime = 30000,
            bleCommand,
            currentPumpStatus.isSuspended()
        )

        aapsLogger.info(LTag.DATABASE, msg.toString())
        val startStopMsg = buildStartStopCommands()
        if (startStopMsg.isNotEmpty()) {
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(
                MedLinkPumpMessage<PumpDriverState, Any>(
                    startStopMsg,
                    btSleepTime
                ), msg
            )
        } else {
            medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(msg)
        }
    }

    private fun handleCalibrationData() {
        lastCalibration = medLinkPumpStatus.nextCalibration
        if (lastCalibration != null) {
            val firstAlarmMinutes =
                preferences.get(MedLinkStringKey.CalibrationFirstAlarm).toLong()
            val secondAlarmMinutes =
                preferences.get(MedLinkStringKey.CalibrationSecondAlarm).toLong()

            when {
                !hasSecondAlert && medLinkPumpStatus.nextCalibration?.minus(
                    secondAlarmMinutes,
                    ChronoUnit.MINUTES
                )!!.isBefore(ZonedDateTime.now()) -> {
                    medtronicUtil.sendNotification(
                        MedtronicNotificationType.SecondCalibrationAlarm, rh, rxBus,
                        medLinkPumpStatus.nextCalibration?.format(DateTimeFormatter.ISO_LOCAL_TIME)
                    )
                    hasSecondAlert = true
                }

                !hasFirstAlert && medLinkPumpStatus.nextCalibration?.minus(
                    firstAlarmMinutes,
                    ChronoUnit.MINUTES
                )!!.isBefore(ZonedDateTime.now()) -> {
                    medtronicUtil.sendNotification(
                        MedtronicNotificationType.FirstCalibrationAlarm, rh, rxBus,
                        medLinkPumpStatus.nextCalibration?.format(DateTimeFormatter.ISO_LOCAL_TIME)
                    )
                    hasFirstAlert = true
                }

                lastCalibration?.isBefore(medLinkPumpStatus.nextCalibration) == true -> {
                    medtronicUtil.dismissNotification(
                        MedtronicNotificationType.SecondCalibrationAlarm,
                        rxBus
                    )
                    medtronicUtil.dismissNotification(
                        MedtronicNotificationType.FirstCalibrationAlarm,
                        rxBus
                    )
                    medtronicUtil.dismissNotification(
                        MedtronicNotificationType.CalibrationSuccess,
                        rxBus
                    )
                    hasFirstAlert = false
                    hasSecondAlert = false
                }
            }
        }
    }

    private fun handleChangeStatusData() {
        if (medLinkPumpStatus.pumpRunningState == PumpRunningState.Running) {
            changeStatusTimestamp = System.currentTimeMillis()
            runBlocking { pumpSync.syncStopTemporaryBasalWithPumpIdMedLink(
                changeStatusTimestamp,
                changeStatusTimestamp,
                pumpType,
                medLinkPumpStatus.serialNumber
            ) }
            changeStatusTimestamp = 0
        }
    }

    var lastReservoirLevel = -1.0
    private fun handleDailyDoseUpdate() {
        if (lastReservoirLevel >= 0.0 && lastReservoirLevel - medLinkPumpStatus.reservoirRemainingUnits > 0.3) {
            readBolusHistory()
        }
        lastReservoirLevel = medLinkPumpStatus.reservoirRemainingUnits
    }

    var previousSensorUpTime: Int = -1

    @SuppressLint("ResourceType")
    fun sensorReInit() {
        if (preferences.get(
                MedLinkBooleanPreferenceKey.HandleSensorChangeEvent
            )
        ) {
            EnliteInterval.clear()
            runBlocking { pumpSync.insertTherapyEventIfNewWithTimestamp(
                medLinkPumpStatus.lastBGTimestamp,
                TE.Type.SENSOR_CHANGE,
                pumpSerial = medLinkServiceData.pumpIDs ?: "00000",
                pumpType = pumpType
            ) }
        }
    }

    fun handleNewSensorData(sens: BgSync.BgHistory) {

        if (sens.bgValue.size == 1) {
            if (sens.bgValue.first().sensorUptime != null) {
                if (previousSensorUpTime > sens.bgValue.first().sensorUptime!!) {
                    sensorReInit()
                } else if (previousSensorUpTime <= 0) {
                    //TODO implement read from status command
                }
                previousSensorUpTime = sens.bgValue.first().sensorUptime!!
            }
        }
        handleNewPumpData()
        if (sens.bgValue.size == 1 &&
            medLinkPumpStatus.needToGetBGHistory() && isInitialized()
        ) {
            missedBGs++
            if (firstMissedBGTimestamp == 0L && isInitialized()) {
                firstMissedBGTimestamp = lastBGHistoryRead
            }
        } else {
            aapsLogger.info(LTag.PUMPBTCOMM, "" + isInitialized)
            aapsLogger.info(LTag.PUMPBTCOMM, "" + isInitialized())
            // medLinkServiceData.
            runBlocking { pumpSync.expectedPumpState() }
            if (isInitialized() && sens.bgValue.size > 1 &&
                sens.bgValue.map { it.timestamp }.min() > firstMissedBGTimestamp &&
                System.currentTimeMillis() - lastPreviousHistory > 1800000 ||
                System.currentTimeMillis() - lastPreviousHistory > 6000000
            ) {
                previousBGHistory()
            }
            missedBGs = 0
            firstMissedBGTimestamp = 0L
        }
        val intent = buildIntentSensValues(sens)
        bgSync.syncBgWithTempId(intent)

        // bgSync.syncBgWithTempId(sens.bgValue, sens.bgCalibration)
        late1Min = if (sens.bgValue.isNotEmpty() && sens.bgValue.first().value != 0.0) {
            readPumpBGHistory(false)
            false
        } else {
            true
        }
    }

    fun handleNewPumpData() {
        medtronicUtil.dismissNotification(MedtronicNotificationType.PumpUnreachable, rxBus)
        handleBatteryData()
        handleChangeStatusData()
        handleDailyDoseUpdate()
        handleProfile()
        postZeroTbrQuestionIfWaiting()
        handleBolusAlarm()
        handleCalibrationData()
        handlePumpStatus()
        handleNewEvent()
    }

    private fun handlePumpStatus() {
        // a plan with microboluses is a TBR above the basal: never a reason to suspend
        val zeroTbrPlan = tempBasalMicrobolusOperations.operations.none { it.operationType == TempBasalMicroBolusPair.OperationType.BOLUS }
        val tbr = temporaryBasal // B12: read once, the plan can change between two reads
        if (zeroTbrPlan && tbr?.rate == 0.0 && tbr.duration > 0 && (medLinkPumpStatus.pumpRunningState == PumpRunningState.Running || medLinkPumpStatus.pumpRunningState == PumpRunningState.Unknow) && !skipSafetyStop()) {
            stopPump(object : Callback() {
                override fun run() {
                    aapsLogger.info(LTag.PUMP, "Stopping unstopped pump")
                }
            })
        }
        if (tbr != null) aapsLogger.info(LTag.PUMP, tbr.toStringFull(dateUtil, rh))
        resumePumpIfLeftSuspended()
    }

    /** B12 / B15: last "pump still suspended" alarm, so a status read every 5 min does not repeat it every time. */
    @Volatile private var lastStillSuspendedAlarmAt = 0L

    /**
     * B12 / B15 ([MedLinkResumeRule]): the app starts the pump again when its own automatic STOP has nobody left to
     * end it (plan lost in an app restart, or the plan REACTIVATE far too late), and alarms when the pump should run
     * already. A suspend by the user is never undone.
     */
    private fun resumePumpIfLeftSuspended() {
        if (medLinkPumpStatus.pumpRunningState == PumpRunningState.Running) {
            clearAutoStop()
            return
        }
        val now = dateUtil.now()
        val autoStopAt = preferences.get(MedtronicLongNonKey.MedLinkAutoStopAt)
        val plan = tempBasalMicrobolusOperations
        val nextReactivate = plan.operations.firstOrNull { it.operationType == TempBasalMicroBolusPair.OperationType.REACTIVATE }
        val action = MedLinkResumeRule.decide(
            now = now,
            pumpSuspended = medLinkPumpStatus.pumpRunningState == PumpRunningState.Suspended ||
                medLinkPumpStatus.pumpRunningState == PumpRunningState.TempBasalSuspended,
            autoStopAt = autoStopAt,
            planHasSteps = plan.firstOrNull() != null,
            nextReactivateAt = nextReactivate?.releaseTime?.toDate()?.time ?: 0L,
            planClearedAt = plan.clearedAt,
            zeroTbrLimitActive = zeroTbrLimitActive()
        )
        if (action == MedLinkResumeRule.Action.NONE) return
        aapsLogger.warn(
            LTag.PUMP,
            "B12: pump suspended since the automatic stop at ${dateUtil.timeString(autoStopAt)}, " +
                "plan ${if (nextReactivate != null) "REACTIVATE at ${nextReactivate.releaseTime.toString("HH:mm:ss")}" else "has no REACTIVATE"}: $action"
        )
        startPump(object : Callback() {
            override fun run() {
                aapsLogger.info(LTag.PUMP, "B12: start of a pump left suspended: ${result?.success}")
            }
        }, true)
        if (action == MedLinkResumeRule.Action.ALARM && now - lastStillSuspendedAlarmAt >= MedLinkResumeRule.ALARM_MARGIN_MS) {
            lastStillSuspendedAlarmAt = now
            uiInteraction.runAlarm(
                rh.gs(R.string.medlink_pump_still_suspended_message, dateUtil.timeString(autoStopAt)),
                rh.gs(R.string.medlink_pump_still_suspended_title),
                app.aaps.core.ui.R.raw.alarm
            )
        }
    }

    private fun handleProfile() {
        // basal of this hour in the last read profile (null: no profile read yet)
        val rate = basalProfile?.getEntryForTime(Instant(medLinkPumpStatus.lastDataTime))?.rate?.let {
            "%.3f".format(it).replace(",", ".").toDouble()
        }
        val basalDiffers = rate != null && rate != medLinkPumpStatus.currentBasal && medLinkPumpStatus.pumpRunningState == PumpRunningState.Running
        val reason = if (basalDiffers) MedLinkProfileReadRule.Reason.BASAL_DIFFERS else MedLinkProfileReadRule.Reason.TIMED
        // B6: read when the basal differs, and every 6 h (a change of IC / ISF / targets is not seen in S)
        if (basalDiffers) aapsLogger.info(LTag.PUMPBTCOMM, "profile basal $rate, pump basal ${medLinkPumpStatus.currentBasal}: basal differs")
        readPumpProfileIfAllowed(reason)
    }

    override fun onProfilesScreenOpened() {
        readPumpProfileIfAllowed(MedLinkProfileReadRule.Reason.SCREEN_OPENED)
    }

    /**
     * B6: one place for every pump profile read (E + F) with [MedLinkProfileReadRule], and a log line for every read
     * and every skip with its reason, so a field log shows why a read did not run. The timed read logs its skips only
     * while no read succeeded yet (it is asked at every status read).
     */
    private fun readPumpProfileIfAllowed(reason: MedLinkProfileReadRule.Reason) {
        val now = System.currentTimeMillis()
        val busy = profileReadBusyReason(now)
        // T19: the interval setting is read at every call, so a change works at the next status read
        val intervalMs = preferences.get(MedLinkMedtronicIntPreferenceKey.ProfileReadIntervalHours) * HOUR_MS
        if (MedLinkProfileReadRule.shouldRead(now, lastProfileRead, lastPumpProfileReadAt, reason, busy != null, intervalMs)) {
            aapsLogger.info(LTag.PUMPBTCOMM, "reading profile: $reason")
            readPumpProfile()
            return
        }
        if (reason == MedLinkProfileReadRule.Reason.TIMED && lastPumpProfileReadAt > 0L) return
        val why = when {
            busy != null                                                                    -> busy
            lastProfileRead > 0L && now - lastProfileRead < MedLinkProfileReadRule.MIN_GAP_MS -> "requested ${(now - lastProfileRead) / 1000} s ago"
            else                                                                            -> "not due (last good read $lastPumpProfileReadAt)"
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "profile read $reason skipped: $why")
    }

    /**
     * B6: why a profile read (E + F, a few seconds) should wait now, or null. A bolus in flight blocks it; a TBR step only
     * when it is due within [PROFILE_READ_TBR_MARGIN_MS] (the plan has a step every few minutes: a longer margin blocked
     * the read nearly all day, field log 2026-11-15; a step after the read only waits a few seconds in the queue).
     */
    private fun profileReadBusyReason(now: Long): String? {
        if (medLinkMedtronicService?.medLinkBLE?.bolusTracker?.hasUnresolved() == true) return "bolus in flight"
        val step = tempBasalMicrobolusOperations.operations.firstOrNull { !it.isCommandIssued && it.releaseTime.toDate().time <= now + PROFILE_READ_TBR_MARGIN_MS }
        return step?.let { "TBR step ${it.operationType} at ${it.releaseTime.toString("HH:mm:ss")}" }
    }

    private fun handleBolusAlarm() {
        if (!medLinkPumpStatus.bgAlarmOn) {
            runBlocking { isClosedLoopAllowed(ConstraintObject(false, aapsLogger)) }
        } else {
            runBlocking { isClosedLoopAllowed(ConstraintObject(true, aapsLogger)) }
        }
    }

    override fun handleBolusDelivered(lastBolusInfo: DetailedBolusInfo?) {
        if (bolusHistoryCheck && lastBolusInfo != null) {
            val state = runBlocking { pumpSync.expectedPumpState() }
            if (lastBolusInfo.insulin == medLinkPumpStatus.lastBolusAmount &&
                medLinkPumpStatus.lastBolusTime != null &&
                state.bolus != null &&
                medLinkPumpStatus.lastBolusTime!!.time > state.bolus?.timestamp!!
            ) {
                aapsLogger.info(
                    LTag.PUMPBTCOMM,
                    "${medLinkPumpStatus.lastBolusTime!!.time} & ${lastBolusInfo.bolusTimestamp}"
                )
                lastBolusInfo.bolusTimestamp = medLinkPumpStatus.lastBolusTime!!.time
                handleNewTreatmentData(of(bolusToJson(lastBolusInfo)))
                bolusHistoryCheck = false
            } else {
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus not delivered")
                aapsLogger.info(LTag.PUMPBTCOMM, bolusToJson(lastBolusInfo).toString())
                aapsLogger.info(LTag.PUMPBTCOMM, "" + medLinkPumpStatus.lastBolusAmount)
                if (result != null && lastBolusInfo.bolusType != BS.Type.TBR && lastBolusInfo.bolusType != BS.Type.SMB) {
                    Thread {
                        SystemClock.sleep(2000)
                        uiInteraction.runAlarm(
                            result!!.comment,
                            rh.gs(R.string.medtronic_cmd_bolus_could_not_be_delivered),
                            app.aaps.core.ui.R.raw.boluserror
                        )
                    }.start()
                }
            }
        }
    }

    private fun handleBatteryData() {
        val currentBatRead = medLinkPumpStatus.lastDataTime
        val currentLevel = receiverStatusStore.batteryLevel
        if (lastBatteryLevel == 0) {
            lastBatteryLevel = currentLevel
        }
        if (suspendBlockedByLowBattery() && currentPumpStatus.isSuspended()) {
            // 4y: MedLink or phone battery low while suspended: start the pump before one of them dies
            clearTempBasal()

        }
        if (batteryLastRead == 0L) {
            batteryLastRead = currentBatRead
        } else {

            val batDelta = (currentBatRead - batteryLastRead) / 60000
            var delta = 0.0
            if (batDelta > 0L) {
                delta = ((currentLevel - lastBatteryLevel) / batDelta).toDouble()
            }
            if (delta > batteryDelta) {
                batteryDelta = delta
            }
        }
        if (medLinkPumpStatus.isBatteryChanged) {
            readBolusHistory()
        }
        medLinkPumpStatus.pumpBatteryRemaining()
    }

    override fun setPumpRunningState(value: PumpRunningState) {
        this.medLinkPumpStatus.pumpRunningState = value
        medLinkPumpStatus.recordRunningState()
        if (value == PumpRunningState.Running) clearAutoStop()
    }

    /** B12: the pump runs again: no automatic stop left to undo. */
    private fun clearAutoStop() {
        if (preferences.get(MedtronicLongNonKey.MedLinkAutoStopAt) != 0L) preferences.put(MedtronicLongNonKey.MedLinkAutoStopAt, 0L)
    }

    override val temporaryBasal: PumpSync.PumpState.TemporaryBasal?
        get() {
            // B12: one read of the first step; `isEmpty()` then `first` crashed the app when a step was removed between them
            val first = tempBasalMicrobolusOperations.firstOrNull()
            if (first != null) {

                val rate = if (tempBasalMicrobolusOperations.absoluteRate == 0.0) {
                    0.0
                } else {
                    medLinkBaseBasalRate
                }

                return PumpSync.PumpState.TemporaryBasal(
                    timestamp = first.releaseTime.toDate().time,
                    duration = tempBasalMicrobolusOperations.durationInMinutes * 60 * 1000L,
                    isAbsolute = false,
                    rate = rate,
                    type = PumpSync.TemporaryBasalType.NORMAL,
                    desiredRate = tempBasalMicrobolusOperations.absoluteRate,
                    id = 0L,
                    pumpId = 0L
                )
            } else {
                return null
            }
        }

    override fun nextScheduledCommand(): String? {
        val nextCommandStream: Stream<MutableMap.MutableEntry<MedLinkMedtronicStatusRefreshType, Long>>? =
            medLinkStatusRefreshMap.entries.stream()
                .sorted { f1: Map.Entry<MedLinkMedtronicStatusRefreshType, Long>, f2: Map.Entry<MedLinkMedtronicStatusRefreshType, Long> ->
                    f1.value.compareTo(
                        f2.value
                    )
                }
        val nextCommand = nextCommandStream?.findFirst()
        var result = ""
        if (nextCommand?.isPresent == true) {
            val key = nextCommand.get().key
            val commandType = key.getCommandType(medtronicUtil.medtronicPumpModel)
            if (commandType != null) {
                result = commandType.commandDescription
                result = result + " " + LocalDateTime(nextCommand.get().value).toString("HH:mm")
            } else {
                result = key.name
                result = result + " " + LocalDateTime(nextCommand.get().value).toString("HH:mm")
            }
        }
        return result
    }

    /**
     * 4ad: basal percentages of the Medtronic tab: pump now, remaining plan, delivered since the start of the clock hour.
     * [tbrBoluses] are the TBR microboluses (time, amount) since [hourStart].
     */
    fun basalPercents(now: Long, hourStart: Long, tbrBoluses: List<Pair<Long, Double>>): Triple<Int?, Int?, Int?> {
        val base = medLinkBaseBasalRate
        val state = medLinkPumpStatus.pumpRunningState
        val plan = tempBasalMicrobolusOperations.let { p ->
            app.aaps.pump.medtronic.data.MedLinkBasalPercent.plan(
                now, p.endsAt, base, state.isSuspended(),
                p.operations.map { app.aaps.pump.medtronic.data.MedLinkBasalPercent.Op(it.releaseTime.toDate().time, it.operationType, it.dose) }
            )
        }
        val hour = app.aaps.pump.medtronic.data.MedLinkBasalPercent.delivered(hourStart, now, base, medLinkPumpStatus.runningTimeline.snapshot(), tbrBoluses)
        return Triple(app.aaps.pump.medtronic.data.MedLinkBasalPercent.pump(state), plan, hour)
    }

    /** Same "plan" % as the MedLink tab (MedLinkBasalPercent.plan), with the start and length of the temp basal. */
    override fun runningTempBasal(now: Long): MedLinkRunningTempBasal? {
        val p = tempBasalMicrobolusOperations
        if (p.endsAt <= now || p.durationInMinutes <= 0) return null
        val percent = MedLinkBasalPercent.plan(
            now, p.endsAt, medLinkBaseBasalRate, medLinkPumpStatus.pumpRunningState.isSuspended(),
            p.operations.map { MedLinkBasalPercent.Op(it.releaseTime.toDate().time, it.operationType, it.dose) }
        ) ?: return null
        val duration = p.durationInMinutes * 60_000L
        return MedLinkRunningTempBasal(
            percent = percent, start = p.endsAt - duration, duration = duration, rate = medLinkBaseBasalRate * percent / 100.0
        )
    }

    override fun tbrDisplayMinutes(): Int = preferences.get(MedLinkMedtronicIntPreferenceKey.TbrDisplayMinutes)

    override fun currentCommandText(): String? {
        val ble = medLinkMedtronicService?.medLinkBLE ?: return null
        val command = ble.shownCommand ?: return null
        val description = ble.shownCommandDescription ?: return null
        // a bolus: only its kind and dose ("SMB 0.30 U"), no command code (user, 2026-11-15)
        return if (ble.shownCommandIsBolus) description else rh.gs(R.string.medlink_command_with_code, command.code, description)
    }

    /** First not sent operation of the TBR plan; without a plan, the next scheduled read (status, history...). */
    override fun nextCommandText(): String? {
        val op = tempBasalMicrobolusOperations.operations.firstOrNull { !it.isCommandIssued }
            ?: return nextScheduledCommand()?.takeIf { it.isNotBlank() }
        val time = op.releaseTime.toString("HH:mm")
        return when (op.operationType) {
            TempBasalMicroBolusPair.OperationType.BOLUS      -> rh.gs(R.string.medlink_next_tbr_bolus, rh.gs(app.aaps.core.ui.R.string.format_insulin_units, op.dose), time)
            TempBasalMicroBolusPair.OperationType.SUSPEND    -> rh.gs(R.string.medlink_next_suspend, time)
            TempBasalMicroBolusPair.OperationType.REACTIVATE -> rh.gs(R.string.medlink_next_reactivate, time)
        }
    }

    override fun isBatteryAge(): Boolean {
        return preferences.get(MedLinkStringPreferenceKey.BatteryInfo) == rh.gs(app.aaps.core.ui.R.string.medtronic_pump_battery_age)
    }

    fun getBatteryTimeDelta(): Long =  System.currentTimeMillis() - medLinkPumpStatus.batteryAge


    fun getBatteryAge(): String = dateUtil.age(getBatteryTimeDelta(), true, rh).replace(" ","")


//    override fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
//        val result = super.getJSONStatus(profile, profileName, version)
//        val battery = JSONObject()
//        try {
//            if (sp.getString(R.string.key_medlink_battery_info, "Age") !== "Age") {
//                battery.put("voltage", pumpStatusData.batteryVoltage)
//                battery.put("percent", batteryLevel)
//
//            } else {
//                val dto = BatteryStatusDTO()
//                dto.voltage = pumpStatusData.batteryVoltage
//                dto.batteryStatusType = BatteryStatusDTO.BatteryStatusType.Unknown
//                val type = BatteryType.valueOf(sp.getString(R.string.key_medtronic_battery_type, BatteryType.None.name))
//                pumpStatusData.deviceBatteryRemaining = dto.getCalculatedPercent(type)
//                battery.put("percent", pumpStatusData.batteryRemaining)
//            }
//            result.put("battery", battery)
//            result.put("clock", dateUtil.toISOString(pumpStatusData.lastDataTime))
//        } catch (e: JSONException) {
//            e.printStackTrace()
//        }
//        return result
//    }

    fun glucoseUnit(): BgSync.GlucoseUnit {
        return if (preferences.get(StringKey.GeneralUnits) == GlucoseUnit.MGDL.asText
        ) BgSync.GlucoseUnit.MGDL
        else BgSync.GlucoseUnit.MMOL
    }

    companion object {
        var isBusy = false

        private const val HOUR_MS = 3_600_000L

        /** T21: the 0 % record is stored with this length and ended at the reconnect. */
        private const val ZERO_TBR_MAX_DURATION_MS = 24 * HOUR_MS
        private const val SCHEDULED_BASAL_STEP_MS = 5 * 60_000L

        /** A TBR bolus step due within this time is sent with an SMB (joined into one pump bolus). */
        private const val TBR_JOIN_SMB_MS = 60_000L

        /** B6: no pump profile read when a TBR step is due within this time (E + F take a few seconds). */
        private const val PROFILE_READ_TBR_MARGIN_MS = 60_000L

        val pumpFreqValues = arrayOf<CharSequence>(
            RileyLinkTargetFrequency.MedtronicUS.key!!,
            RileyLinkTargetFrequency.MedtronicWorldWide.key!!
        )
        val encodingValues = arrayOf<CharSequence>(
            RileyLinkEncodingType.FourByteSixByteLocal.key!!,
            RileyLinkEncodingType.FourByteSixByteRileyLink.key!!
        )
        val batteryValues =
            mutableListOf<CharSequence>().also { list -> BatteryType.entries.forEach { list.add(it.key) } }
                .toTypedArray()

    }

    override suspend fun onStart() {
        displayConnectionMessages = false
        // injector?.let {
        MedLinkStatusParser.parseStatus(
            emptyArray<String>(),
            medLinkPumpStatus,
            aapsLogger
        )
        // }
        serviceConnection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) {
                aapsLogger.debug(LTag.PUMP, "MedLinkMedtronicService is disconnected")
                // medLinkService = null
            }

            override fun onServiceConnected(name: ComponentName, ibinder: IBinder) {
                aapsLogger.debug(LTag.PUMP, "MedLinkMedtronicService is connected")
                val mLocalBinder = ibinder as MedLinkMedtronicService.LocalBinder
                medLinkMedtronicService = mLocalBinder.serviceInstance
                isServiceSet = true
                installBolusSafetyHooks()
                medLinkMedtronicService!!.verifyConfiguration()
                Thread {
                    for (i in 0..19) {
                        SystemClock.sleep(5000)
                        aapsLogger.debug(LTag.PUMP, "Starting Medtronic-MedLink service")
                        if (medLinkMedtronicService?.setNotInPreInit() == true) {
                            aapsLogger.debug("MedlinkService setnotinpreinit")
                            break
                        }
                    }
                }.start()
            }
        }
        super.onStart()
    }


//    override var pumpDeviceState = PumpDeviceState.NeverContacted

    // override fun setPumpDeviceState(state: PumpDeviceState) {
    //     medLinkService?.setPumpDeviceState(state)
    // }

    override fun setMedtronicPumpModel(model: String) {
        medtronicUtil.medtronicPumpModel = MedLinkMedtronicDeviceType.getByDescription(model)
    }

    override fun setBatteryLevel(batteryLevel: Int) {
        medLinkPumpStatus.medLinkBatteryRemaining = batteryLevel
    }

    private fun removeStopCommands() {
        val message = MedLinkPumpMessage<PumpDriverState, Any>(
            MedLinkCommandType.RemoveStopCommand,
            BleCommand(
                aapsLogger,
                medLinkMedtronicService?.medLinkServiceData
            ), CommandPriority.HIGH
        )
        medLinkMedtronicService?.medtronicUIComm?.executeCommandCP(message)
    }

    private enum class BolusDeliveryType {
        Idle,  //
        DeliveryPrepared,  //
        Delivering,  //
        CancelDelivery
    }


    private val pumpFreqEntries = arrayOf<CharSequence>(
        rh.gs(RileyLinkTargetFrequency.MedtronicUS.friendlyName!!),
        rh.gs(RileyLinkTargetFrequency.MedtronicWorldWide.friendlyName!!)
    )


    // AAPS 4.0: Compose preferences (was addPreferenceScreen with the View preference widgets)
    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "medtronic_settings",
        titleResId = R.string.medtronic_name,
        items = listOf(
            MedtronicStringPreferenceKey.Serial,
            MedtronicStringPreferenceKey.PumpType,
            MedtronicStringPreferenceKey.PumpFrequency,
            MedtronicIntPreferenceKey.MaxBasal,
            MedtronicIntPreferenceKey.MaxBolus,
            MedtronicIntPreferenceKey.BolusDelay,
            MedtronicStringPreferenceKey.BatteryType,
            // stored values are the localized texts, as before the merge
            MedLinkStringPreferenceKey.BatteryInfo.withEntries(
                listOf(app.aaps.core.ui.R.string.medtronic_pump_battery_age, app.aaps.core.ui.R.string.medtronic_pump_battery_volts)
                    .associate { rh.gs(it) to rh.gs(it) }
            ),
            MedLinkIntentPreferenceKey.MacAddressSelector.withActivity(MedLinkBLEConfigActivity::class.java),
            MedLinkBooleanPreferenceKey.ChangeCannulaAction,
            MedLinkBooleanPreferenceKey.HandleSensorChangeEvent,
            MedLinkBooleanPreferenceKey.HandleCannulaChangeEvent,
            MedLinkBooleanPreferenceKey.LastBolusHistory,
            MedLinkBooleanPreferenceKey.PreviousBolusHistory,
            MedLinkBooleanPreferenceKey.LastBgHistory,
            MedLinkBooleanPreferenceKey.PreviousBgHistory,
            MedLinkBooleanPreferenceKey.ForceBolusHistoryReadAfter,
            MedtronicBooleanPreferenceKey.SetNeutralTemp,
            MedLinkMedtronicIntPreferenceKey.CommandAttempts,
            MedLinkMedtronicIntPreferenceKey.MinBatteryForSuspend,
            MedLinkMedtronicIntPreferenceKey.TbrDisplayMinutes,
            MedLinkMedtronicBooleanPreferenceKey.MinBasalAfterBolusEnabled,
            MedLinkMedtronicIntPreferenceKey.MinBasalAfterBolusPercent,
            MedLinkMedtronicIntPreferenceKey.MinBasalAfterBolusMinutes,
            MedLinkMedtronicDoublePreferenceKey.MinBasalAfterBolusDelta,
            MedLinkMedtronicIntPreferenceKey.ProfileReadIntervalHours,
            MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarm,
            MedLinkMedtronicIntPreferenceKey.ConnectionAlarmFailures,
            MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarmSound,
            MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarmTorch
        ),
        icon = pluginDescription.icon
    )

}