package app.aaps.pump.medtronic.data

/**
 * B12 / B15: what a status read does when the pump is suspended.
 *
 * On the Veo a suspend through MedLink has no end time: only the app starts the pump again. The TBR plan lives only
 * in memory, so an app crash or restart loses its REACTIVATE (field night 2026-11-16: suspended from 02:30 to 06:00).
 *
 * The app starts the pump only when its own last STOP was automatic (TBR plan). A suspend by the user, in the app
 * or on the pump, is never undone by the app. Starting the pump only gives the normal basal back: no extra insulin.
 */
object MedLinkResumeRule {

    /** After an automatic STOP or a cleared plan: time for a new plan to be made before the app acts. */
    const val GRACE_MS = 5 * 60_000L

    /** A planned REACTIVATE this late, or no start this long after [GRACE_MS]: the user gets an alarm. */
    const val ALARM_MARGIN_MS = 10 * 60_000L

    enum class Action {
        NONE,

        /** start the pump */
        START,

        /** start the pump and alarm the user: the pump should already run */
        ALARM
    }

    /**
     * @param pumpSuspended the status read says suspended (not "unknown")
     * @param autoStopAt when the pump confirmed the last automatic STOP; 0 = the last stop was by the user, or the
     *                   pump was started after it. Kept over an app restart.
     * @param planHasSteps the TBR plan still has steps
     * @param nextReactivateAt time of the next REACTIVATE step of the plan; 0 = none
     * @param planClearedAt when the plan was last cleared (a new plan clears the old one first); 0 = never
     * @param zeroTbrLimitActive T21 waits for the user answer about a 0 % basal: the app does nothing here
     */
    fun decide(
        now: Long,
        pumpSuspended: Boolean,
        autoStopAt: Long,
        planHasSteps: Boolean,
        nextReactivateAt: Long,
        planClearedAt: Long,
        zeroTbrLimitActive: Boolean,
    ): Action {
        if (!pumpSuspended || autoStopAt <= 0L || zeroTbrLimitActive) return Action.NONE
        if (planHasSteps && nextReactivateAt > 0L) {
            // the plan starts the pump itself; only a REACTIVATE far too late is a problem
            return if (now > nextReactivateAt + ALARM_MARGIN_MS) Action.ALARM else Action.NONE
        }
        // no plan (lost in a restart, or ended) or a plan with no REACTIVATE
        val since = now - maxOf(autoStopAt, planClearedAt)
        return when {
            since >= GRACE_MS + ALARM_MARGIN_MS -> Action.ALARM
            since >= GRACE_MS                   -> Action.START
            else                                -> Action.NONE
        }
    }
}
