package info.nightscout.androidaps.plugins.pump.common.hw.medlink.data

import app.aaps.core.data.iob.EnliteInMemoryGlucoseValue
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.BgSync
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import java.time.ZonedDateTime

open class MedLinkPartialBolus(pumpType: PumpType?) : PumpStatus(pumpType!!), MedLinkPumpStatus {


    override fun initSettings() {}
    override val errorInfo: String?
        get() = null
    override val lastBolusInfo: DetailedBolusInfo?
        get() = this.lastBolusInfo
    override var lastReadingStatus: BGReadingStatus
        get() = this.lastReadingStatus
        set(value) {this.lastReadingStatus = value}
    override var pumpDeviceState: PumpDeviceState
        get() = pumpDeviceState
        set(value) {pumpDeviceState=value}
    override var bolusDeliveredAmount: Double?
        get() = bolusDeliveredAmount
        set(value) {bolusDeliveredAmount=value}
    override var latestBG: Double
        get() = latestBG
        set(value) {latestBG=value}
    override val lastBatteryChanged: Long
        get() = lastBatteryChanged
    override var isBatteryChanged: Boolean
        get() = isBatteryChanged
        set(value) {isBatteryChanged=value}
    override var currentBasal: Double
        get() = currentBasal
        set(value) {currentBasal=value}
    override var tempBasalRemainMin: Int
        get() = tempBasalRemainMin
        set(value) {tempBasalRemainMin=value}
    override var yesterdayTotalUnits: Double?
        get() = yesterdayTotalUnits
        set(value) {yesterdayTotalUnits=value}
    override var sensorAge: Int?
        get() = sensorAge
        set(value) {sensorAge=value}
    override var isig: Double?
        get() = isig
        set(value) {isig=value}
    override var bgReading: EnliteInMemoryGlucoseValue
        get() = bgReading
        set(value) {bgReading=value}
    override var sensorDataReading: BgSync.BgHistory.BgValue
        get() = sensorDataReading
        set(value) {sensorDataReading=value}
    override var calibrationFactor: Double?
        get() = calibrationFactor
        set(value) {calibrationFactor=value}
    override var nextCalibration: ZonedDateTime?
        get() = nextCalibration
        set(value) {nextCalibration=value}
    override var bgAlarmOn: Boolean
        get() = bgAlarmOn
        set(value) {bgAlarmOn=value}
    override var lastBGTimestamp: Long
        get() = lastBGTimestamp
        set(value) {lastBGTimestamp=value}

    override fun toString(): String {
        return "MedLinkPartialBolus{" +
            "bolusDeliveredAmount=" + bolusDeliveredAmount +
            ", lastBolusTime=" + lastBolusTime +
            ", lastBolusAmount=" + lastBolusAmount +
            '}'
    }
}
