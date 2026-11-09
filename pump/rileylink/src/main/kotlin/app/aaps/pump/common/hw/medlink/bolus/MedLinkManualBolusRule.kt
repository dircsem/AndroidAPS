package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType

/**
 * T15 / 4i: which new bolus is refused, and how long a manual bolus may wait.
 *
 *  - a new bolus is refused only when a bolus of the same type is pending (queued, merged into
 *    another one, or sent and not resolved): [refuses];
 *  - a bolus of another type is merged (pump has not received the pending one) or waits for `M`
 *    and runs right after it (see MedLinkBLE);
 *  - T16 (in the plugin): an SMB is still refused while a manual bolus is pending, it was computed
 *    without the manual bolus in IOB; a manual bolus with an SMB pending asks the user first;
 *  - there is no time to live: a waiting bolus runs when the one in flight is resolved, which is bounded by
 *    the tries of that bolus and its `M` failures (see MedLinkBolusTracker).
 */
object MedLinkManualBolusRule {

    /** Is a bolus of [newType] refused while boluses of [pendingTypes] are pending? */
    fun refuses(pendingTypes: Set<MedLinkCommandType>, newType: MedLinkCommandType): Boolean = newType in pendingTypes

    /** Amount the user may set in the T16 dialog: lower than or equal to the requested one (already within limits), on the pump step. */
    fun editedAmount(requested: Double, entered: Double?, step: Double): Double? {
        if (entered == null || entered.isNaN() || step <= 0.0) return null
        val rounded = kotlin.math.floor(entered / step + 1e-6) * step
        val value = kotlin.math.round(rounded * 1000.0) / 1000.0
        return value.takeIf { it > 0.0 && it <= requested + 1e-9 }
    }
}
