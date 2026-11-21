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

    /** B15: the guard never sounds sooner than this after the last sign of life of the app (user, 2026-11-19). */
    const val APP_ALIVE_MARGIN_MS = 20 * 60_000L

    /**
     * B15: when the guard (an Android alarm) must sound if the pump still is not started. It is only for a dead or
     * frozen app: while the app works, [decide] acts and alarms itself, and every sign of life moves the guard
     * [APP_ALIVE_MARGIN_MS] ahead (user, 2026-11-19: no extra alarms at night).
     * @param autoStopAt see [decide]; 0 = no automatic stop: no guard
     * @param nextReactivateAt the next REACTIVATE of the plan (or the AAPS TBR end); 0 or before the stop = none
     * @param now the time of this sign of life of the app
     * @return the alarm time, or 0 = no guard
     */
    fun guardAlarmAt(autoStopAt: Long, nextReactivateAt: Long, now: Long): Long {
        if (autoStopAt <= 0L) return 0L
        val planned = if (nextReactivateAt > autoStopAt) nextReactivateAt + ALARM_MARGIN_MS else autoStopAt + GRACE_MS + ALARM_MARGIN_MS
        return maxOf(planned, now + APP_ALIVE_MARGIN_MS)
    }

    /**
     * User, 2026-11-19: below the low mark the app does not start a suspended pump by itself before the loop has run
     * after the stop (or after the last plan change): at a low BG the loop may want the pump to stay stopped (field
     * 2026-11-19: BG 85 falling, the loop asked 0 U/h every 5 min). After the loop run the normal rule acts. No BG or an
     * old BG ([bgMgdl] null) does not wait: a long CGM gap must not keep the pump stopped. While it waits, the alarm
     * still comes at the normal alarm time, without a START ([Action.ALARM_ONLY]): the loop may be dead.
     */
    fun waitsForLoop(autoStopAt: Long, planClearedAt: Long, bgMgdl: Double?, lowMarkMgdl: Double, lastLoopRunAt: Long): Boolean =
        bgMgdl != null && bgMgdl < lowMarkMgdl && lastLoopRunAt <= maxOf(autoStopAt, planClearedAt)

    /**
     * End of an AAPS TBR when it gives less than the basal (so the pump is stopped for some of its time), else 0.
     * @param basalUh the profile basal now, for an absolute TBR; null = unknown (then only 0 U/h counts)
     */
    fun lowTbrEndAt(start: Long, durationMs: Long, rate: Double, isAbsolute: Boolean, basalUh: Double?): Long {
        val below = when {
            !isAbsolute                      -> rate < 100.0
            rate <= 0.0                      -> true
            basalUh == null || basalUh <= 0.0 -> false
            else                             -> rate < basalUh
        }
        return if (below) start + durationMs else 0L
    }

    /**
     * B15: at most one guard alarm for each automatic STOP (user, 2026-11-19). A reminder the user asked for with
     * "Silence 30 min" ([isSnooze]) always shows.
     * @param firedForStop the STOP time of the last guard alarm shown; 0 = none
     */
    fun guardShouldShow(stoppedAt: Long, firedForStop: Long, isSnooze: Boolean): Boolean =
        isSnooze || stoppedAt <= 0L || stoppedAt != firedForStop

    enum class Action {
        NONE,

        /** start the pump */
        START,

        /** start the pump and alarm the user: the pump should already run */
        ALARM,

        /** alarm the user, no START: BG below the low mark and the loop has not run since the stop ([waitsForLoop]) */
        ALARM_ONLY
    }

    /**
     * @param pumpSuspended the status read says suspended (not "unknown")
     * @param autoStopAt when the pump confirmed the last automatic STOP; 0 = the last stop was by the user, or the
     *                   pump was started after it. Kept over an app restart.
     * @param planHasSteps the TBR plan still has steps
     * @param nextReactivateAt time of the next REACTIVATE step of the plan; 0 = none
     * @param planClearedAt when the plan was last cleared (a new plan clears the old one first); 0 = never
     * @param zeroTbrLimitActive T21 waits for the user answer about a 0 % basal: the app does nothing here
     * @param bgMgdl the current BG in mg/dL; null = no BG or old BG (then the rule acts as without a BG, user 2026-11-19)
     * @param lowMarkMgdl the low mark of the BG graph (`OverviewLowMark`), in mg/dL
     * @param lastLoopRunAt the last loop calculation; 0 = none since the app start
     * @param databaseTbrEndAt end of the last TBR below 100 % of the AAPS database ([lowTbrEndAt]): running now, or
     *                         ended (then the START waits for a loop run after the end); 0 = none. After an app restart the plan (in memory) is lost, but this TBR is what the
     *                         loop asked: no START before its end (field 2026-11-19: started at 14:24, TBR 0 % to 14:45).
     */
    fun decide(
        now: Long,
        pumpSuspended: Boolean,
        autoStopAt: Long,
        planHasSteps: Boolean,
        nextReactivateAt: Long,
        planClearedAt: Long,
        zeroTbrLimitActive: Boolean,
        bgMgdl: Double?,
        lowMarkMgdl: Double,
        lastLoopRunAt: Long,
        databaseTbrEndAt: Long,
    ): Action {
        if (!pumpSuspended || autoStopAt <= 0L || zeroTbrLimitActive) return Action.NONE
        val action = normalAction(now, autoStopAt, planHasSteps, nextReactivateAt, planClearedAt, databaseTbrEndAt, lastLoopRunAt)
        if (!waitsForLoop(autoStopAt, planClearedAt, bgMgdl, lowMarkMgdl, lastLoopRunAt)) return action
        // low BG, no loop run yet: never START; at the normal alarm time the user is told (the loop may be dead)
        return if (action == Action.ALARM) Action.ALARM_ONLY else Action.NONE
    }

    private fun normalAction(
        now: Long, autoStopAt: Long, planHasSteps: Boolean, nextReactivateAt: Long, planClearedAt: Long, databaseTbrEndAt: Long,
        lastLoopRunAt: Long,
    ): Action {
        if (planHasSteps && nextReactivateAt > 0L) {
            // the plan starts the pump itself; only a REACTIVATE far too late is a problem
            return if (now > nextReactivateAt + ALARM_MARGIN_MS) Action.ALARM else Action.NONE
        }
        // an AAPS TBR end before the stop belongs to an older suspension: not used
        val tbrEnd = if (databaseTbrEndAt > autoStopAt) databaseTbrEndAt else 0L
        // no plan, but AAPS still has a TBR below 100 % running: the loop wants the pump (partly) stopped until its end.
        // Suspended until then = the asked insulin or a little less, never more.
        if (tbrEnd > now) return Action.NONE
        // no plan (lost in a restart, or ended) or a plan with no REACTIVATE; counted from the AAPS TBR end too
        val since = now - maxOf(autoStopAt, planClearedAt, tbrEnd)
        return when {
            since >= GRACE_MS + ALARM_MARGIN_MS -> Action.ALARM
            // after an AAPS TBR end the loop decides first (field 2026-11-19 17:00: START 3 s before the loop asked 0 %)
            since >= GRACE_MS && (tbrEnd == 0L || lastLoopRunAt > tbrEnd) -> Action.START
            else                                -> Action.NONE
        }
    }
}
