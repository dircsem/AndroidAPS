package app.aaps.pump.medtronic.data.dto


/**
 * Created by Dirceu on 18/01/21.
 * used by medlink
 */
class MedLinkBasalProfileEntry(basalAmount: Double, hour: Int, minute: Int) :
    BasalProfileEntry(basalAmount, hour, minute) {
    val time: String?

    init {
        this.rate = basalAmount
        time = "" + hour + ":" + minute
    }
}
