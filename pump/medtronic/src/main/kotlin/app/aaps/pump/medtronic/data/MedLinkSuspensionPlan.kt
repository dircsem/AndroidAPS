package app.aaps.pump.medtronic.data

import app.aaps.core.data.configuration.Constants
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import org.joda.time.LocalDateTime
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The MedLink pump (Veo) has no temp basal below 100 % through MedLink. A TBR below the basal is made of suspensions:
 * the pump is stopped (SUSPEND) and started again (REACTIVATE) so that the minutes stopped give the asked rate.
 *
 * B14 (moved out of `MedLinkMedtronicPumpPlugin`, field night 2026-11-16): the old builder dropped suspensions
 * (`mergeExtraOperations`: 10 min suspended where 17 were asked, more insulin than the loop asked) and gave no plan
 * at all when the active part was below 5 min (10 % for 30 min ran at 100 %).
 *
 * Rules of a plan:
 * - The suspended minutes are exactly the asked minutes. When the active part is shorter than
 *   [MIN_SEGMENT_MINUTES] it can not be split: the pump is suspended for the whole TBR (a little less insulin, never more).
 * - Each suspension and each active part between two suspensions is at least [MIN_SEGMENT_MINUTES] (fewer STOP / START).
 * - Steps always come as SUSPEND, REACTIVATE, SUSPEND, REACTIVATE, ...; the last REACTIVATE is at or before the TBR end.
 * - The start follows the clock ([MedLinkSuspendStartRule]): a plan made during the active part of the cycle starts
 *   active for the minutes left of it, so planning again every 5 min does not suspend more.
 */
object MedLinkSuspensionPlan {

    /** Shortest suspension, and shortest active part between two suspensions. */
    const val MIN_SEGMENT_MINUTES = Constants.INTERVAL_BETWEEN_OPERATIONS

    data class Step(val type: OperationType, val at: LocalDateTime)

    /**
     * @param suspendMinutes length of one suspension (the first ones can be 1 min longer to give the exact total)
     * @param activeMinutes length of one active part between two suspensions
     * @param activeMinutesLeft the plan starts active for this long; 0 = suspended at once
     * @param steps empty = nothing to suspend
     */
    data class Plan(val suspendMinutes: Int, val activeMinutes: Int, val activeMinutesLeft: Int, val steps: List<Step>)

    /**
     * Minutes to suspend in [durationMinutes] so that the pump gives [deliveredFraction] of its basal
     * (absolute TBR / basal, or percent / 100). Rounded half up, between 0 and [durationMinutes].
     */
    fun suspendedMinutes(durationMinutes: Int, deliveredFraction: Double): Int {
        if (durationMinutes <= 0) return 0
        val stopped = durationMinutes * (1.0 - deliveredFraction.coerceIn(0.0, 1.0))
        return BigDecimal(stopped.toString()).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, durationMinutes)
    }

    /**
     * @param suspendedMinutes minutes to suspend (see [suspendedMinutes]); below [MIN_SEGMENT_MINUTES] there is no plan
     * @param durationMinutes length of the TBR
     * @param start when the plan starts (now, or the old end of a plan that is extended, T24)
     */
    fun build(suspendedMinutes: Int, durationMinutes: Int, start: LocalDateTime): Plan {
        if (durationMinutes <= 0 || suspendedMinutes < MIN_SEGMENT_MINUTES) return Plan(0, 0, 0, emptyList())
        val suspended = minOf(suspendedMinutes, durationMinutes)
        val active = durationMinutes - suspended
        if (active < MIN_SEGMENT_MINUTES) {
            // the active part can not be split in parts of 5 min: suspended the whole TBR (less insulin, never more)
            return Plan(durationMinutes, 0, 0, listOf(Step(OperationType.SUSPEND, start), Step(OperationType.REACTIVATE, start.plusMinutes(durationMinutes))))
        }
        // as many suspensions as fit: each suspension and each active part between them at least 5 min
        val count = maxOf(1, minOf(suspended / MIN_SEGMENT_MINUTES, active / MIN_SEGMENT_MINUTES))
        val suspendEach = suspended / count
        val suspendExtra = suspended % count
        val activeEach = active / count
        // the start follows the clock; activeMinutesLeft <= activeEach, so all suspensions end before the TBR end
        val activeLeft = MedLinkSuspendStartRule.activeMinutesLeft(start.toDate().time, suspendEach, activeEach)
        val steps = ArrayList<Step>(count * 2)
        var at = start.plusMinutes(activeLeft)
        for (i in 0 until count) {
            val length = suspendEach + if (i < suspendExtra) 1 else 0
            steps.add(Step(OperationType.SUSPEND, at))
            at = at.plusMinutes(length)
            steps.add(Step(OperationType.REACTIVATE, at))
            at = at.plusMinutes(activeEach)
        }
        return Plan(suspendEach, activeEach, activeLeft, steps)
    }
}
