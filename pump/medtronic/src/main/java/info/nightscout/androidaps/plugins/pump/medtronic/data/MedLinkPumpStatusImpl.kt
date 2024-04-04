package info.nightscout.androidaps.plugins.pump.medtronic.data

import app.aaps.core.data.iob.EnliteInMemoryGlucoseValue
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.BgSync.BgHistory.BgValue
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.data.MedLinkPartialBolus
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.data.MedLinkPumpStatusCallback
import info.nightscout.pump.common.sync.PumpDbEntryTBR
import java.time.ZonedDateTime

/**
 * Created by Dirceu on 21/01/21.
 */
open class MedLinkPumpStatusImpl(pumpType: PumpType?) : MedLinkPartialBolus(pumpType), MedLinkPumpStatusCallback {

    override var sensorAge: Int? = null
    override var isig: Double? = null
    override var bgReading: EnliteInMemoryGlucoseValue
        get() = TODO("Not yet implemented")
        set(value) {}
    override var sensorDataReading: BgValue
        get() = TODO("Not yet implemented")
        set(value) {}
    override var calibrationFactor: Double? = null
    override var yesterdayTotalUnits: Double? = null
    var deviceBatteryVoltage = 0.0
    var deviceBatteryRemaining = 0
    override var nextCalibration: ZonedDateTime? = null
    override var bgAlarmOn = false
    override var currentBasal = 0.0
    override var tempBasalRemainMin = 0
    var runningTBR: PumpDbEntryTBR? = null
    override var isBatteryChanged = false
        set(batteryChanged) {
            lastBatteryChanged = System.currentTimeMillis()
            field = batteryChanged
        }
    override var lastBatteryChanged: Long = 0

    override fun toString(): String {
        return "MedLinkPumpStatus{" +
            "sensorAge=" + sensorAge +
            ", isig=" + isig +
            ", calibrationFactor=" + calibrationFactor +
            ", deviceBatteryVoltage=" + deviceBatteryVoltage +
            ", deviceBatteryRemaining=" + deviceBatteryRemaining +
            ", nextCalibration=" + nextCalibration +
            ", bgAlarmOn=" + bgAlarmOn +
            ", lastReadingStatus=" + lastReadingStatus +
            ", currentReadingStatus=" + currentReadingStatus +
            ", lastBGTimestamp=" + lastBGTimestamp +
            ", latestBG=" + latestBG +
            ", bolusDeliveredAmount=" + bolusDeliveredAmount +
            ", lastConnection=" + lastConnection +
            ", lastBolusTime=" + lastBolusTime +
            ", lastBolusAmount=" + lastBolusAmount +
            '}'
    }


    override var lastReadingStatus = BGReadingStatus.FAILED
    override var pumpDeviceState: PumpDeviceState
        get() = pumpDeviceState
        set(value) { this.pumpDeviceState = value }
    var currentReadingStatus = BGReadingStatus.FAILED
    override var lastBGTimestamp: Long = 0
    override var latestBG = 0.0
    override val lastBolusInfo: DetailedBolusInfo
        get() {
            val result = DetailedBolusInfo()
            lastBolusAmount?.let {  result.insulin = it}

            if (lastBolusTime != null) {
                result.deliverAtTheLatest = lastBolusTime!!.time
            }
            return result
        } //    public PumpStatusType getPumpStatusType(){
    //        return pumpStatusType;
    //    }
}
