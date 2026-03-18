package app.aaps.pump.common.hw.medlink.data

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import java.util.Date

open class MedLinkPartialBolus()  {

  var lastBolusInfo: DetailedBolusInfo?= DetailedBolusInfo()
        get() {
            val result = DetailedBolusInfo()
            field?.let {  result.insulin = it.insulin}

            if (lastBolusTime != null) {
                result.deliverAtTheLatest = lastBolusTime!!.time
            }
            return result
        }
    var lastReadingStatus: BGReadingStatus = BGReadingStatus.UNKNOW
    var pumpDeviceState: PumpDeviceState = PumpDeviceState.NeverContacted
    var lastBolusAmount = 0.0
    var bolusDeliveredAmount: Double? = 0.0
    var lastBolusTime: Date?=null




    override fun toString(): String {
        return "MedLinkPartialBolus{" +
            "bolusDeliveredAmount=" + bolusDeliveredAmount +
            ", lastBolusTime=" + lastBolusTime +
            ", lastBolusAmount=" + lastBolusAmount +
            '}'
    }
}