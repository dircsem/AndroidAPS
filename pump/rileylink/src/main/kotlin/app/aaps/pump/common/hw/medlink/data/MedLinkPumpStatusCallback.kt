package app.aaps.pump.common.hw.medlink.data

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import java.util.Date

interface MedLinkPumpStatusCallback {

    var lastBolusTime: Date?
    val lastBolusInfo: DetailedBolusInfo?
    var bolusDeliveredAmount: Double?
    var lastBolusAmount: Double?

}
