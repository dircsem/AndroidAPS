package info.nightscout.androidaps.plugins.pump.common.hw.medlink.data

import app.aaps.core.data.iob.EnliteInMemoryGlucoseValue
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.BgSync
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.pump.PumpRunningState
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import java.time.ZonedDateTime
import java.util.Date

open class MedLinkPartialBolus(pumpType: PumpType?) : PumpStatus(pumpType!!), MedLinkPumpStatus {

    override var lastBolusInfo: DetailedBolusInfo?= DetailedBolusInfo()
        get() {
            val result = DetailedBolusInfo()
            field?.let {  result.insulin = it.insulin}

            if (lastBolusTime != null) {
                result.deliverAtTheLatest = lastBolusTime!!.time
            }
            return result
        }
    override var lastReadingStatus: BGReadingStatus = BGReadingStatus.UNKNOW
    override var pumpDeviceState: PumpDeviceState = PumpDeviceState.NeverContacted
    override var bolusDeliveredAmount: Double? = 0.0
    override var latestBG: Double= 0.0
    override var lastBatteryChanged: Long= 0
    override var isBatteryChanged: Boolean= false
    override var currentBasal: Double= 0.0
    override var tempBasalRemainMin: Int= 0
    override var yesterdayTotalUnits: Double?= 0.0
    override var sensorAge: Int?= 0
    override var isig: Double?= 0.0

    var bgRead: EnliteInMemoryGlucoseValue = EnliteInMemoryGlucoseValue()
    override var bgReading: EnliteInMemoryGlucoseValue? =null


    override lateinit var sensorDataReading: BgSync.BgHistory.BgValue

    override var calibrationFactor: Double?= 0.0
    override var nextCalibration: ZonedDateTime?=null
    override var bgAlarmOn: Boolean=false
    override var lastBolusTime: Date?=null
    override var lastBGTimestamp: Long= 0


    override fun initSettings() {}
    override var errorInfo: String?=null


    override fun toString(): String {
        return "MedLinkPartialBolus{" +
            "bolusDeliveredAmount=" + bolusDeliveredAmount +
            ", lastBolusTime=" + lastBolusTime +
            ", lastBolusAmount=" + lastBolusAmount +
            '}'
    }
}
