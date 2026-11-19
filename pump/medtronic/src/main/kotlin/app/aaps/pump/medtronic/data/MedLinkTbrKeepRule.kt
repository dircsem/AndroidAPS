package app.aaps.pump.medtronic.data

/**
 * T24: what to do with the running TBR plan when the loop sends a new TBR. The loop sends a TBR every 5 min; a new
 * plan every time means more STOP / START and TBR boluses moved around (pump battery).
 *
 * User rule (2026-11-16): the TBR percent of the running plan is stored. A new TBR with the SAME percent:
 * - less than [REPLAN_AFTER_MS] after the plan was made: keep the plan as it is (it still covers the next minutes);
 * - after that: keep the steps already planned, and plan only the period from the old end to the new end.
 * A different percent: a new plan, as before. The same for TBR boluses (above 100 %) and STOP / START (below 100 %).
 */
object MedLinkTbrKeepRule {

    const val REPLAN_AFTER_MS = 15 * 60_000L

    enum class Action {

        /** Clear the plan and build a new one (as before T24). */
        REBUILD,

        /** Keep the plan as it is. */
        KEEP,

        /** Keep the planned steps and add the steps from the old end to the new end. */
        EXTEND
    }

    data class Decision(val action: Action, val reason: String)

    /** The percent of an absolute TBR (U/h) over the basal, as a whole number. 0 when there is no basal. */
    fun percentOf(rateUh: Double, basalUh: Double): Int =
        if (basalUh <= 0.0) 0 else Math.round(rateUh / basalUh * 100.0).toInt()

    /**
     * @param hasPlan the running plan has steps left
     * @param planPercent the percent the running plan was made for
     * @param plannedAt when the running plan was made or last extended
     * @param planEnd when the running plan ends
     * @param newEnd when the new TBR ends
     */
    fun decide(now: Long, hasPlan: Boolean, planPercent: Int, newPercent: Int, plannedAt: Long, planEnd: Long, newEnd: Long): Decision = when {
        !hasPlan                           -> Decision(Action.REBUILD, "no plan")
        newPercent == 0 || newPercent == 100 -> Decision(Action.REBUILD, "$newPercent %")
        planPercent != newPercent          -> Decision(Action.REBUILD, "$planPercent % -> $newPercent %")
        planEnd <= now                     -> Decision(Action.REBUILD, "plan ended")
        now - plannedAt < REPLAN_AFTER_MS  -> Decision(Action.KEEP, "same $newPercent %, plan made ${(now - plannedAt) / 60_000} min ago")
        newEnd <= planEnd                  -> Decision(Action.KEEP, "same $newPercent %, plan already covers the new TBR")
        else                               -> Decision(Action.EXTEND, "same $newPercent %, plan made ${(now - plannedAt) / 60_000} min ago")
    }
}
