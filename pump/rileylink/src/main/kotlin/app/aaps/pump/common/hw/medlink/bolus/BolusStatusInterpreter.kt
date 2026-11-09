package app.aaps.pump.common.hw.medlink.bolus

import kotlin.math.abs

data class BolusOutcome(
    val type: Type,
    val deliveredAmount: Double = 0.0,
    val pumpBolusTime: Long? = null,
    val reason: String = ""
) {

    enum class Type {

        /** The pump has no bolus newer than the baseline: the only outcome that may allow a re-send. */
        NOT_STARTED,
        DELIVERING,
        DELIVERED,
        PARTIAL,
        UNKNOWN
    }
}

/**
 * What we know about the bolus that was sent.
 *
 * @param baseline          `M` answer read before the bolus command was written
 * @param expectedEndAt     phone time when the full dose should be delivered
 * @param pumpClockOffsetMs pump clock minus phone clock, used only when there is no baseline
 */
data class BolusCheck(
    val requested: Double,
    val sentAt: Long,
    val baseline: BolusStatusSnapshot?,
    val expectedEndAt: Long,
    val pumpClockOffsetMs: Long = 0L
)

/**
 * The single interpreter of `M` answers (T5).
 *
 * Safety rule: NOT_STARTED is the only outcome that may lead to a re-send, so it is returned only
 * when the answer is unambiguous. Anything doubtful is UNKNOWN (read again / alarm, never re-send).
 */
object BolusStatusInterpreter {

    // 0.05 U plus a small epsilon so 1.0 - 0.95 is inside the tolerance
    const val TOLERANCE = 0.05 + 1e-9
    private const val MINUTE = 60_000L

    fun interpret(snapshot: BolusStatusSnapshot?, check: BolusCheck, now: Long): BolusOutcome {
        if (snapshot == null) return BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "no M answer")

        val age = age(snapshot, check)
        if (age != Age.NEW && isSentBolusOnSquareLine(snapshot, check)) {
            // 4o: the "last bolus" line is still the previous bolus, the sent one runs on the square line
            if (snapshot.inProgress)
                return BolusOutcome(BolusOutcome.Type.DELIVERING, snapshot.deliveredAmount ?: 0.0, null, "sent bolus running on the square line")
            // over on the square line, "last bolus" not updated yet: never call it not started (no re-send)
            return BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "sent bolus ended on the square line, last bolus not updated yet")
        }
        when (age) {
            Age.OLD       -> return BolusOutcome(BolusOutcome.Type.NOT_STARTED, reason = "last bolus is not newer than baseline")
            Age.AMBIGUOUS -> return BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "cannot tell last bolus from the sent one")
            Age.NEW       -> Unit
        }

        if (snapshot.inProgress) {
            val programmed = snapshot.squareBolusAmount?.takeIf { it > 0.0 } ?: snapshot.lastBolusAmount
            if (programmed > check.requested + TOLERANCE)
                return BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "bolus in progress ($programmed U) is bigger than requested")
            return BolusOutcome(BolusOutcome.Type.DELIVERING, snapshot.deliveredAmount ?: 0.0, snapshot.lastBolusTime)
        }

        val amount = snapshot.lastBolusAmount
        return when {
            amount > check.requested + TOLERANCE      ->
                BolusOutcome(BolusOutcome.Type.UNKNOWN, reason = "newer bolus ($amount U) is bigger than requested")

            abs(amount - check.requested) <= TOLERANCE ->
                BolusOutcome(BolusOutcome.Type.DELIVERED, amount, snapshot.lastBolusTime)

            now < check.expectedEndAt                  ->
                BolusOutcome(BolusOutcome.Type.DELIVERING, amount, snapshot.lastBolusTime, "below requested before expected end")

            else                                       ->
                BolusOutcome(BolusOutcome.Type.PARTIAL, amount, snapshot.lastBolusTime)
        }
    }

    /**
     * A normal bolus on the square line, read after the bolus was sent, not bigger than the request, that the baseline
     * did not show: the sent bolus (the pump shows a running normal bolus there until it becomes the "last bolus").
     * A bigger one is another bolus: the tracker waits for it to end.
     */
    fun isSentBolusOnSquareLine(snapshot: BolusStatusSnapshot, check: BolusCheck): Boolean =
        snapshot.normalBolusOnSquareLine && snapshot.readAt >= check.sentAt && snapshot.squareBolusAmount!! <= check.requested + TOLERANCE &&
            check.baseline?.let { it.normalBolusOnSquareLine && it.inProgress } != true

    private enum class Age { OLD, AMBIGUOUS, NEW }

    private fun age(snapshot: BolusStatusSnapshot, check: BolusCheck): Age {
        val baseline = check.baseline
        val sentPumpTime = check.sentAt + check.pumpClockOffsetMs
        if (baseline != null) {
            return when {
                snapshot.lastBolusTime > baseline.lastBolusTime  -> Age.NEW
                snapshot.lastBolusTime < baseline.lastBolusTime  -> Age.AMBIGUOUS
                // same time as baseline: the baseline bolus must be clearly before the send minute,
                // otherwise a bolus applied in the same pump minute would look "not started"
                abs(sentPumpTime - baseline.lastBolusTime) < MINUTE -> Age.AMBIGUOUS
                abs(snapshot.lastBolusAmount - baseline.lastBolusAmount) > TOLERANCE -> Age.AMBIGUOUS
                else                                              -> Age.OLD
            }
        }
        return when {
            snapshot.lastBolusTime >= sentPumpTime - MINUTE     -> Age.NEW
            snapshot.lastBolusTime < sentPumpTime - 2 * MINUTE  -> Age.OLD
            else                                                -> Age.AMBIGUOUS
        }
    }
}
