package app.aaps.pump.medtronic

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import dagger.android.support.DaggerFragment
import app.aaps.pump.common.hw.medlink.MedLinkUtil
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.pump.common.hw.medlink.dialog.MedLinkStatusActivity
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData

import app.aaps.pump.medtronic.util.MedLinkMedtronicUtil
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

import app.aaps.core.interfaces.pump.WarnColors
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventQueueChanged
import app.aaps.core.interfaces.rx.events.EventRefreshButtonState
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.common.hw.medlink.defs.MedLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.medtronic.databinding.MedlinkMedtronicFragmentBinding
import app.aaps.pump.medtronic.dialog.MedLinkMedtronicHistoryActivity
import app.aaps.pump.medtronic.events.EventMedtronicPumpConfigurationChanged
import app.aaps.pump.medtronic.events.EventMedtronicPumpValuesChanged
import app.aaps.pump.common.events.EventMedLinkDeviceStatusChange

import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.*
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

class MedLinkMedtronicFragment : DaggerFragment() {

    @Inject
    lateinit var preferences: Preferences

    @Inject
    lateinit var persistenceLayer: PersistenceLayer
    @Inject
    lateinit var aapsLogger: AAPSLogger

    @Inject
    lateinit var fabricPrivacy: FabricPrivacy

    @Inject
    lateinit var rh: ResourceHelper

    @Inject
    lateinit var rxBus: RxBus

    @Inject
    lateinit var commandQueue: CommandQueue

    @Inject
    lateinit var activePlugin: ActivePlugin

    @Inject
    lateinit var medLinkMedtronicPumpPlugin: MedLinkMedtronicPumpPlugin

    @Inject
    lateinit var warnColors: WarnColors

    @Inject
    lateinit var medLinkUtil: MedLinkUtil

    @Inject
    lateinit var dateUtil: DateUtil

    @Inject
    lateinit var medLinkMedtronicUtil: MedLinkMedtronicUtil

    //    @Inject lateinit var medtronicPumpStatus: MedLinkMedtronicPumpStatus
    @Inject
    lateinit var medLinkServiceData: MedLinkServiceData

    @Inject
    lateinit var aapsSchedulers: AapsSchedulers

    private var disposable: CompositeDisposable = CompositeDisposable()

    private val handler =
        Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private lateinit var refreshLoop: Runnable

    init {
        refreshLoop = Runnable {
            activity?.runOnUiThread { updateGUI() }
            handler.postDelayed(refreshLoop, T.mins(1).msecs())
        }
    }

    private var _binding: MedlinkMedtronicFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        MedlinkMedtronicFragmentBinding.inflate(inflater, container, false)
            .also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        super.onViewCreated(view, savedInstanceState)

        binding.medtronicRlStatus.text = rh.gs(MedLinkServiceState.NotStarted.resourceId)

        binding.medtronicRlStatus.setTextColor(Color.WHITE)
        @SuppressLint("SetTextI18n")
        binding.medtronicRlStatus.text = "{fa-bed}"

        binding.history.setOnClickListener {
            if (medLinkMedtronicPumpPlugin.medLinkService?.verifyConfiguration() == true) {
                startActivity(Intent(context, MedLinkMedtronicHistoryActivity::class.java))
            } else {
                displayNotConfiguredDialog()
            }
        }

        binding.refresh.setOnClickListener {
            if (medLinkMedtronicPumpPlugin.medLinkService?.verifyConfiguration() != true) {
                displayNotConfiguredDialog()
            } else {
                binding.refresh.isEnabled = false
                medLinkMedtronicPumpPlugin.resetStatusState()
                commandQueue.readStatus(rh.gs(R.string.clicked_refresh), object : Callback() {
                    override fun run() {
                        activity?.runOnUiThread {
                            if (_binding != null) binding.refresh.isEnabled = true
                        }
                    }
                })
            }
        }

        binding.stats.setOnClickListener {
            if (medLinkMedtronicPumpPlugin.medLinkService?.verifyConfiguration() == true) {
                startActivity(Intent(context, MedLinkStatusActivity::class.java))
            } else {
                displayNotConfiguredDialog()
            }
        }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        handler.postDelayed(refreshLoop, T.mins(1).msecs())
        disposable += rxBus
            .toObservable(EventRefreshButtonState::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ binding.refresh.isEnabled = it.newState }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventMedLinkDeviceStatusChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                aapsLogger.debug(LTag.PUMP, "onStatusEvent(EventRileyLinkDeviceStatusChange): $it")
                setDeviceStatus()
            }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventMedtronicPumpValuesChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventMedtronicPumpConfigurationChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                aapsLogger.debug(LTag.PUMP, "EventMedtronicPumpConfigurationChanged triggered")
                medLinkMedtronicPumpPlugin.medLinkService?.verifyConfiguration()
                updateGUI()
            }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventQueueChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)

        updateGUI()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacks(refreshLoop)
    }

    @Synchronized
    private fun setDeviceStatus() {
        val resourceId = medLinkServiceData.medLinkServiceState.resourceId
        val medLinkError: MedLinkError? = medLinkMedtronicPumpPlugin.medLinkService?.error

        binding.medtronicRlStatus.text =
            when {
                medLinkServiceData.medLinkServiceState == MedLinkServiceState.NotStarted -> rh.gs(
                    resourceId
                )

                medLinkServiceData.medLinkServiceState.isConnecting -> "{fa-bluetooth-b spin}   " + rh.gs(
                    resourceId
                )

                medLinkServiceData.medLinkServiceState.isError && medLinkError == null -> "{fa-bluetooth-b}   " + rh.gs(
                    resourceId
                )

                medLinkServiceData.medLinkServiceState.isError && medLinkError != null -> "{fa-bluetooth-b}   " + rh.gs(
                    medLinkError.getResourceId(RileyLinkTargetDevice.MedtronicPump)
                )

                else -> "{fa-bluetooth-b}   " + rh.gs(resourceId)
            }
        binding.medtronicRlStatus.setTextColor(if (medLinkError != null) Color.RED else Color.WHITE)

        binding.medtronicErrors.text =
            medLinkServiceData.medLinkError?.let {
                rh.gs(it.getResourceId(RileyLinkTargetDevice.MedtronicPump))
            } ?: "-"

        when (medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpDeviceState) {
            null -> binding.medtronicPumpStatus.text = PumpDeviceState.ErrorWhenCommunicating.name

            PumpDeviceState.Sleeping,
                -> {
                binding.medtronicPumpStatus.text =
                    "{fa-bed}   " + medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpDeviceState.toString()
            }

            PumpDeviceState.NeverContacted,
                -> binding.medtronicPumpStatus.text =
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpDeviceState.toString()

            PumpDeviceState.WakingUp,
            PumpDeviceState.PumpUnreachable,
            PumpDeviceState.ErrorWhenCommunicating,
            PumpDeviceState.TimeoutWhenCommunicating,
            PumpDeviceState.InvalidConfiguration,
                -> binding.medtronicPumpStatus.text =
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpDeviceState.toString()

            PumpDeviceState.Active -> {
                val cmd = medLinkMedtronicUtil.currentCommand
                if (cmd == null)
                    binding.medtronicPumpStatus.text =
                        medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpDeviceState.toString()
                else {
                    aapsLogger.debug(LTag.PUMP, "Command: " + cmd)
                    val cmdResourceId = cmd.resourceId
                    if (cmd == MedLinkCommandType.GetState) {
                        binding.medtronicPumpStatus.text =
                            rh.gs(R.string.medtronic_cmd_desc_get_settings)
                    } else {
                        binding.medtronicPumpStatus.text = buildString {
                            append(" ")
                            append(cmdResourceId.let {
                                if (it > 0) {
                                    rh.gs(it)
                                } else {
                                    cmd.getCommandDescription()
                                }
                            })

                        }
                    }
                }
            }

            else -> {
                $"{fa-bed}   (PumpDeviceState.Sleeping.name)".also {
                    binding.medtronicPumpStatus.text = it
                }
                aapsLogger.warn(
                    LTag.PUMP,
                    "Unknown pump state: " + medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpDeviceState
                )
            }
        }

        var id =
            if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpRunningState == PumpRunningState.Running) {
                app.aaps.core.ui.R.string.medtronic_pump_state_RUNNING;
            } else {
                app.aaps.core.ui.R.string.medtronic_pump_state_SUSPENDED;
            }
        binding.medtronicPumpState.text = rh.gs(id)

        val status = commandQueue.spannedStatus()
        if (status.toString() == "") {
            binding.medtronicQueue.visibility = View.GONE
        } else {
            binding.medtronicQueue.visibility = View.VISIBLE
            binding.medtronicQueue.text = status
        }
    }

    private fun displayNotConfiguredDialog() {
        context?.let {
            UIRunnable {
                OKDialog.show(
                    it, rh.gs(R.string.medtronic_warning),
                    rh.gs(R.string.medtronic_error_operation_not_possible_no_configuration) + "fragment"
                )
            }
        }
    }

    // GUI functions
    @Synchronized
    fun updateGUI() {

        setDeviceStatus()

        // last connection
        if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.lastConnection != 0L) {
            val age =
                System.currentTimeMillis() - medLinkMedtronicPumpPlugin.medLinkPumpStatus.lastConnection
            binding.medtronicLastconnection.text = dateUtil.age(age, true, rh)
            val minAgo = age / 60000
            if (minAgo < 5) {
                binding.medtronicLastconnection.setTextColor(Color.WHITE)
                binding.medtronicRlStatus.setTextColor(Color.WHITE)
            } else if (minAgo < 10) {
                binding.medtronicLastconnection.setTextColor(Color.YELLOW)
            } else {
                binding.medtronicLastconnection.setTextColor(Color.RED)

            }
        }

        // last bolus
        val bolus = medLinkMedtronicPumpPlugin.medLinkPumpStatus.lastBolusAmount
        val bolusTime = medLinkMedtronicPumpPlugin.medLinkPumpStatus.lastBolusTime
        if (bolus != null && bolusTime != null) {
            val unit = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
            val ago = deltaTimeAgo(bolusTime.time)
            binding.medtronicLastbolus.text = rh.gs(R.string.mdt_last_bolus, bolus, unit, ago)
        } else {
            binding.medtronicLastbolus.text = ""
        }

        // base basal rate
        binding.medtronicBasabasalrate.text = (buildString {
            append(
                "(${
                    medLinkMedtronicPumpPlugin.medLinkPumpStatus.activeProfileName.uppercase(
                        Locale.getDefault()
                    )
                })  "
            )
            append(medLinkMedtronicPumpPlugin.baseBasalRate)
        })

        binding.medtronicTempbasal.text =
            medLinkMedtronicPumpPlugin.medLinkPumpStatus.tempBasalAmount.toString()


        // battery
        if (medLinkMedtronicPumpPlugin.isBatteryAge()) {

            val batteryLevel = (medLinkMedtronicPumpPlugin.batteryLevel ?: 75) / 25
            binding.medtronicPumpstateBattery.text = buildString {
                append("{fa-battery-")
                append(batteryLevel)
                append("} ")
                append(medLinkMedtronicPumpPlugin.getBatteryAge())
                append(" Wz")
            }
            persistenceLayer.getLastTherapyEvent(TE.Type.PUMP_BATTERY_CHANGE).let {
                if(it!=null) {
                    warnColors.setColorByAge(
                        binding.medtronicPumpstateBattery,
                        it,
                        preferences.get(IntKey.OverviewBageWarning),
                        preferences.get(IntKey.OverviewBageCritical)
                    )
                }
            }
        } else
            if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.batteryRemaining == 0 || medLinkMedtronicPumpPlugin.medLinkPumpStatus.batteryVoltage == 0.0) {
                binding.medtronicPumpstateBattery.text = "{fa-battery-0}"
            } else {
                (buildString {
                    String.format(
                        $$"%.2f V", medLinkMedtronicPumpPlugin.medLinkPumpStatus.batteryVoltage
                    )
                }).also { binding.medtronicPumpstateBattery.text = it }
            }
//        warnColors.setColorInverse(
//            binding.medtronicPumpstateBattery,
//            medLinkMedtronicPumpPlugin.medLinkPumpStatus.batteryRemaining!!.toDouble(),
//            25,
//            10
//        )

        // reservoir
        if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.reservoirRemainingUnits < 10.0) {
            binding.medtronicReservoir.text = rh.gs(
                app.aaps.core.ui.R.string.reservoir_low_value,
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.reservoirRemainingUnits,
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.reservoirFullUnits
            )
        } else {
            binding.medtronicReservoir.text = rh.gs(
                app.aaps.core.ui.R.string.reservoir_value,
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.reservoirRemainingUnits,
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.reservoirFullUnits
            )
        }
        warnColors.setColorInverse(
            binding.medtronicReservoir,
            medLinkMedtronicPumpPlugin.medLinkPumpStatus.reservoirRemainingUnits,
            50,
            20
        )

        medLinkMedtronicPumpPlugin.medLinkService?.verifyConfiguration()
        binding.medtronicErrors.text = medLinkMedtronicPumpPlugin.medLinkPumpStatus.errorInfo

        // next command
        val pump = activePlugin.activePump
        if (pump is MedLinkMedtronicPumpPlugin) {
            if (pump.temporaryBasal != null) {
                ("${
                    pump.tempBasalMicrobolusOperations.operations.peek()?.toStringView()
                } \n${pump.nextScheduledCommand()}").also { binding.medtronicNextCommand.text = it }
            } else {
                binding.medtronicNextCommand.text = pump.nextScheduledCommand()
            }
        }

        medLinkMedtronicPumpPlugin.medLinkService?.verifyConfiguration()
        binding.medtronicErrors.text = medLinkMedtronicPumpPlugin.medLinkPumpStatus.errorInfo

        //totalDailyInsulin
        val todayTotalUnits = medLinkMedtronicPumpPlugin.medLinkPumpStatus.dailyTotalUnits
        val yesterdayTotalUnits = medLinkMedtronicPumpPlugin.medLinkPumpStatus.yesterdayTotalUnits
        aapsLogger.info(LTag.EVENTS, "today yesterday$todayTotalUnits $yesterdayTotalUnits")
        if (todayTotalUnits != null && yesterdayTotalUnits != null) {
            binding.medtronicTotalInsulin.text = "$todayTotalUnits/$yesterdayTotalUnits"
        } else {
            binding.medtronicTotalInsulin.text = ""
        }

        //DeviceBattery
        val deviceBatteryRemaining =
            medLinkMedtronicPumpPlugin.medLinkPumpStatus.medLinkBatteryRemaining ?: 75
        val deviceBatteryVoltage = medLinkMedtronicPumpPlugin.medLinkPumpStatus.deviceBatteryVoltage
        aapsLogger.info(LTag.EVENTS, "device battery$deviceBatteryRemaining $deviceBatteryVoltage")


        binding.medtronicDevicestateBattery.text =
            "{fa-battery-${deviceBatteryRemaining!! / 25} }  ${deviceBatteryRemaining}%";

        warnColors.setColorInverse(
            binding.medtronicDevicestateBattery,
            deviceBatteryRemaining.toDouble(),
            25,
            10
        )

        // next calibration
        if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.nextCalibration != null) {
            val agoMsc = medLinkMedtronicPumpPlugin.medLinkPumpStatus.nextCalibration!!.toInstant()
                .toEpochMilli() - System.currentTimeMillis()
            val calibrationMinAgo = agoMsc.toDouble() / 60.0 / 1000.0

            val ago = if (agoMsc < 60 * 1000) {
                rh.gs(R.string.medtronic_pump_connected_now)
            } else if (calibrationMinAgo < 60) {
                dateUtil.minAfter(
                    rh,
                    medLinkMedtronicPumpPlugin.medLinkPumpStatus.nextCalibration!!.toInstant()
                        .toEpochMilli()
                ).toString()
            } else {
                dateUtil.hourAfter(
                    medLinkMedtronicPumpPlugin.medLinkPumpStatus.nextCalibration!!.toInstant()
                        .toEpochMilli(), rh
                ).toString()
            }
            binding.medtronicNextcalibration.text = rh.gs(
                R.string.mdt_next_calibration,
                ago,
                medLinkMedtronicPumpPlugin.medLinkPumpStatus.nextCalibration!!.toLocalTime()
            )
            warnColors.setColorInverse(binding.medtronicNextcalibration, calibrationMinAgo, 60, 30)
        } else {
            binding.medtronicNextcalibration.text = ""
        }

        // isig
        if (!activePlugin.activeBgSource.isMedLink) {
            binding.medtronicIsigLayout.isVisible = false
            binding.medtronicIsigView.isVisible = false
            binding.medtronicNextCalibrationLayout.isVisible = false
            binding.medtronicNextCalibrationView.isVisible = false

        } else
            if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.isig != null && medLinkMedtronicPumpPlugin.medLinkPumpStatus.isig == 0.0) {
                binding.medtronicIsig.text =
                    rh.gs(app.aaps.pump.common.hw.rileylink.R.string.sensor_lost)
                binding.medtronicIsig.setTextColor(Color.RED)
            } else if (medLinkMedtronicPumpPlugin.medLinkPumpStatus.isig != null) {
                binding.medtronicIsig.text =
                    String.format("%.2fnA", medLinkMedtronicPumpPlugin.medLinkPumpStatus.isig)
                warnColors.setColorInverse(
                    binding.medtronicIsig,
                    medLinkMedtronicPumpPlugin.medLinkPumpStatus.isig!!,
                    8,
                    7
                )
                warnColors.setColor(
                    binding.medtronicIsig,
                    medLinkMedtronicPumpPlugin.medLinkPumpStatus.isig!!,
                    26.0,
                    31.0
                )
            } else {
                binding.medtronicIsig.text = ""
            }
    }

    private fun deltaTimeAgo(timeInMilli: Long): String {
        val agoMsc =
            System.currentTimeMillis() - timeInMilli
        return dateUtil.age(agoMsc, true, rh).replace(" ","")
    }
}
