package info.nightscout.androidaps.plugins.pump.common.hw.medlink.data

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import java.util.Date

interface MedLinkPumpStatusCallback {

    abstract var lastBolusTime: Date?
    abstract val lastBolusInfo: DetailedBolusInfo
    abstract var bolusDeliveredAmount: Double?
    abstract var lastBolusAmount: Double?

}
