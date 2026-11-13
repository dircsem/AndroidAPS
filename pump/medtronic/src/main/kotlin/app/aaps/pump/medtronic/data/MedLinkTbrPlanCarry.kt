package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import kotlin.math.max

/**
 * The loop sends a new TBR every 5 min. Each new TBR builds a new MedLink plan. Without this rule the first
 * operation of the plan moved later on every new TBR (a single TBR bolus is placed at the middle of the
 * TBR, 15 min from now) and it never happened.
 *
 * Rules for a new plan (all times are epoch ms):
 * 1. Same direction: when the old plan still had its first operation pending (BOLUS -> new plan starts
 *    with BOLUS, SUSPEND -> new plan starts with SUSPEND), the new first operation gets the earlier of
 *    the two times. A SUSPEND moves together with its REACTIVATE, so the suspended time does not change.
 *    The other operations stay as they are.
 * 2. Spacing guard (bolus plans): the first TBR bolus is not earlier than
 *    `last TBR bolus time + last TBR bolus dose / extra rate`, so the boluses do not give more than the TBR
 *    asked for. When the first bolus moves later, the next boluses move by the same time. A bolus that
 *    moves after the end of the TBR is dropped.
 */
object MedLinkTbrPlanCarry {

    private const val HOUR_MS = 3_600_000.0

    data class Op(val type: OperationType, val releaseAt: Long, val dose: Double = 0.0, val calculatedDose: Double = 0.0)

    /** The last TBR bolus given (or being given now). */
    data class LastBolus(val at: Long, val dose: Double)

    /**
     * @param ops the new plan, in time order
     * @param previousFirst first pending (not yet sent) operation of the old plan, null if none
     * @param lastTbrBolus last TBR bolus, null if none
     * @return new release time for each op of [ops], null = drop the op
     */
    fun releaseTimes(
        ops: List<Op>,
        previousFirst: Op?,
        lastTbrBolus: LastBolus?,
        durationInMinutes: Int,
        bolusStep: Double,
        now: Long
    ): List<Long?> {
        val times: MutableList<Long?> = ops.map { it.releaseAt }.toMutableList()
        val first = ops.firstOrNull() ?: return times

        // rule 1: keep the earlier first operation
        if (previousFirst != null && previousFirst.type == first.type && first.type != OperationType.REACTIVATE &&
            previousFirst.releaseAt < first.releaseAt
        ) {
            val delta = first.releaseAt - previousFirst.releaseAt
            times[0] = previousFirst.releaseAt
            if (first.type == OperationType.SUSPEND && ops.size > 1 && ops[1].type == OperationType.REACTIVATE)
                times[1] = ops[1].releaseAt - delta
        }

        // rule 2: spacing guard, bolus plans only
        if (lastTbrBolus == null || first.type != OperationType.BOLUS || durationInMinutes <= 0) return times
        val extraRate = extraRatePerHour(ops, durationInMinutes, bolusStep)
        if (extraRate <= 0.0) return times
        val earliest = lastTbrBolus.at + (lastTbrBolus.dose / extraRate * HOUR_MS).toLong()
        val firstAt = times[0] ?: return times
        val shift = earliest - firstAt
        if (shift <= 0) return times
        val end = now + durationInMinutes * 60_000L
        for (i in times.indices) {
            val at = times[i] ?: continue
            // the first bolus gets exactly `earliest`, the next ones keep their distance to it
            val moved = if (i == 0) earliest else max(at + shift, earliest)
            times[i] = if (moved > end) null else moved
        }
        return times
    }

    /**
     * Insulin above the base basal per hour, from the not rounded dose of each bolus of the plan.
     * `calculatedDose` is the not rounded amount of one pump step, a bolus of 0.2 U has 2 steps.
     */
    fun extraRatePerHour(ops: List<Op>, durationInMinutes: Int, bolusStep: Double): Double =
        if (durationInMinutes <= 0 || bolusStep <= 0.0) 0.0
        else ops.filter { it.type == OperationType.BOLUS }
            .sumOf { it.calculatedDose * Math.round(it.dose / bolusStep) } / (durationInMinutes / 60.0)
}
