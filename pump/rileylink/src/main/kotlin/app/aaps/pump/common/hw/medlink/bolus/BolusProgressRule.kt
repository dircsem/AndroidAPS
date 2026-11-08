package app.aaps.pump.common.hw.medlink.bolus

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Root cause 7: decides whether the bolus followed by [app.aaps.pump.common.hw.medlink.activities.BolusProgressCallback]
 * is done. `square bolus: 0.0u` only means "nothing in progress"; whether *our* bolus was applied is
 * decided by comparing the pump's last bolus with the last bolus known before sending (pump clock).
 *
 * Without a baseline the pump time cannot be compared with the phone time (unknown clock offset), so
 * the bolus counts as delivered only when nothing is in progress and the amount equals the request.
 */
object BolusProgressRule {

    fun decide(
        snapshot: BolusStatusSnapshot?,
        requested: Double,
        baselineTime: Long?,
        baselineAmount: Double?,
        createdAt: Long,
        now: Long
    ): BolusOutcome {
        if (snapshot == null) return BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "no M answer")
        if (baselineTime == null || baselineTime <= 0L) {
            return when {
                snapshot.inProgress                                                          ->
                    BolusOutcome(BolusOutcome.Type.DELIVERING, snapshot.deliveredAmount ?: 0.0, snapshot.lastBolusTime)

                abs(snapshot.lastBolusAmount - requested) <= BolusStatusInterpreter.TOLERANCE ->
                    BolusOutcome(BolusOutcome.Type.DELIVERED, snapshot.lastBolusAmount, snapshot.lastBolusTime)

                else                                                                         ->
                    BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "no baseline and amount ${snapshot.lastBolusAmount} != $requested")
            }
        }
        val baseline = snapshot.copy(
            lastBolusAmount = baselineAmount ?: snapshot.lastBolusAmount,
            lastBolusTime = baselineTime,
            squareBolusAmount = 0.0,
            deliveredAmount = 0.0,
            pumpBolusing = false
        )
        // sentAt is unknown here (the callback is built before queuing); use a value far from the
        // baseline minute so an unchanged last bolus is judged "not started" by the baseline alone
        val check = BolusCheck(
            requested = requested,
            sentAt = maxOf(createdAt, baselineTime + 2 * BolusTracker.MINUTE),
            baseline = baseline,
            expectedEndAt = createdAt + (requested * BolusTracker.MS_PER_UNIT).roundToLong() + BolusTracker.DELIVERY_MARGIN_MS
        )
        return BolusStatusInterpreter.interpret(snapshot, check, now)
    }
}
