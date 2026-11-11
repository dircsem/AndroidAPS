package app.aaps.pump.medtronic.data

import app.aaps.core.data.iob.EnliteInMemoryGlucoseValue
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.source.BgSync
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.data.PumpStatus
import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.common.hw.medlink.data.MedLinkPumpStatusCallback
import app.aaps.pump.common.hw.medlink.keys.MedLinkStringKey
import app.aaps.pump.common.sync.PumpDbEntryTBR
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.data.dto.BatteryStatusDTO
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.medtronic.keys.MedtronicStringPreferenceKey
import java.time.ZonedDateTime

/**
 * Created by Dirceu on 21/01/21.
 */
open class MedLinkPumpStatusImpl(val preferences: Preferences, pumpType: PumpType?, val aapsLogger: AAPSLogger) :
    PumpStatus(pumpType!!), MedLinkPumpStatus,
    MedLinkPumpStatusCallback {

    fun needToGetBGHistory(): Boolean {
        return lastDataTime - lastBGTimestamp > 360000
    }

    var errorDescription: String? = null

    override var sensorAge: Int? = null
    override var isig: Double? = null
    override var bgReading: EnliteInMemoryGlucoseValue? = null

    override lateinit var sensorDataReading: BgSync.BgHistory.BgValue


    override var calibrationFactor: Double? = null
    override var yesterdayTotalUnits: Double? = null
//    override var dailyTotalUnits: Double? = null
//

    var deviceBatteryVoltage = 0.0

    override var nextCalibration: ZonedDateTime? = null
    override var bgAlarmOn = false
    override var currentBasal = 0.0

    //    override var activeProfileName: String = ""
    override var tempBasalRemainMin = 0

    //    override var tempBasalAmount: Double? =null
    var runningTBR: PumpDbEntryTBR? = null
    override var isBatteryChanged = false
        get() = field
        set(batteryChanged) {
            lastBatteryChanged = System.currentTimeMillis()
            field = batteryChanged
        }
    override var lastBatteryChanged: Long = 0

    override fun toString(): String {
        return "MedLinkPumpStatus{" +
                sensorAge?.let { "sensorAge=" + it } +
                isig?.let { ", isig=" + it } +
                calibrationFactor?.let { ", calibrationFactor=" + it } +
                ", deviceBatteryVoltage=" + deviceBatteryVoltage +
                batteryRemaining?.let { ", deviceBatteryRemaining=" + it } +
                nextCalibration?.let { ", nextCalibration=" + it } +
                ", bgAlarmOn=" + bgAlarmOn +
                ", lastReadingStatus=" + lastReadingStatus +
                ", currentReadingStatus=" + currentReadingStatus +
                ", lastBGTimestamp=" + lastBGTimestamp +
                ", latestBG=" + latestBG +
                bolusDeliveredAmount?.let { ", bolusDeliveredAmount=" + it } +
                ", lastConnection=" + lastConnection +
                ", lastBolusTime=" + lastBolusTime +
                ", lastBolusAmount=" + lastBolusAmount +
                '}'
    }

    override var readProfile: Long = -1

    fun lastBolusInfoProcess(): DetailedBolusInfo {
        val result = DetailedBolusInfo()
        result.insulin = lastBolusAmount ?: 0.0

        if (lastBolusTime != null) {
            aapsLogger.info(LTag.PUMPBTCOMM,"deliver at the latest set")
            result.deliverAtTheLatest = lastBolusTime!!.time
        }
        return result

    }

    override val lastBolusInfo: DetailedBolusInfo
        get() = lastBolusInfoProcess()


    override var lastReadingStatus = BGReadingStatus.FAILED
    override var pumpDeviceState: PumpDeviceState = PumpDeviceState.NeverContacted
    override var bolusDeliveredAmount: Double? = null

    var currentReadingStatus = BGReadingStatus.FAILED
    override var lastBGTimestamp: Long = 0
    /** 4ad: pump running state changes, for the "this hour" basal percentage of the Medtronic tab */
    val runningTimeline = app.aaps.pump.medtronic.data.MedLinkBasalPercent.Timeline()

    fun recordRunningState() = runningTimeline.record(System.currentTimeMillis(), pumpRunningState)

    override fun setPumpRunning() {
        pumpRunningState = PumpRunningState.Running
        recordRunningState()
    }

    override fun setPumpSuspended() {
        pumpRunningState = PumpRunningState.Suspended
        recordRunningState()
    }

    override fun setPumpUnknow() {
        this.pumpRunningState = PumpRunningState.Unknow
        recordRunningState()
    }

    override var latestBG = 0.0
    override val errorInfo: String?
        get() = if (errorDescription == null) "-" else errorDescription!!


    override var medLinkBatteryRemaining: Int? = null

    override fun pumpBatteryRemaining() {

        batteryVoltage.let {

            val dto = BatteryStatusDTO()
            dto.batteryStatusType = BatteryStatusDTO.BatteryStatusType.Unknown
            dto.voltage = it!!
            val type =
                BatteryType.getByKey(preferences.get(MedtronicStringPreferenceKey.BatteryType))
            val remaining = dto.getCalculatedPercent(type)
            batteryRemaining = remaining
            remaining
        }
    }
}
