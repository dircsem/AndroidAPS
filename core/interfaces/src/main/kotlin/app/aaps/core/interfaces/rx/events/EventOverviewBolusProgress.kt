package app.aaps.core.interfaces.rx.events

import app.aaps.core.interfaces.R
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.resources.ResourceHelper
import kotlin.math.min

/**
 * Custom status message and percent
 *
 * @param waiting the bolus has not started yet (connecting, sending...): the dialog shows a moving bar instead of the percent
 */
class EventOverviewBolusProgress(status: String, val id: Long? = null, percent: Int? = null, waiting: Boolean = false) : Event() {

    init {
        if (id == BolusProgressData.id || id == null) {
            BolusProgressData.status = status
            percent?.let { BolusProgressData.percent = it }
            BolusProgressData.waiting = waiting
        }
    }

    /**
     * Delivering %1$.2fU and percent is calculated
     */
    constructor(rh: ResourceHelper, delivered: Double, id: Long? = null) :
        this(
            rh.gs(R.string.bolus_delivering, delivered),
            id = id,
            percent = min((delivered / BolusProgressData.insulin * 100).toInt(), 100)
        )

    /**
     * For 100%: Bolus %1$.2fU delivered successfully.
     * else: Delivering %1$.2fU.
     */
    constructor(rh: ResourceHelper, percent: Int, id: Long? = null) :
        this(
            status =
                if (percent == 100) rh.gs(R.string.bolus_delivered_successfully, BolusProgressData.insulin)
                else rh.gs(R.string.bolus_delivering, BolusProgressData.insulin * percent / 100.0),
            id = id,
            percent = min(percent, 100)
        )
}