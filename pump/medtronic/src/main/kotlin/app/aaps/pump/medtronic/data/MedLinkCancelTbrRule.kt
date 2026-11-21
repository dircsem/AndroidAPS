package app.aaps.pump.medtronic.data

/**
 * What a TBR cancel does with the pump (field 2026-11-19 16:41-16:51; user rule 2026-11-19).
 *
 * With a plan (as before): START when the pump is suspended, or the plan stopped it, or a REACTIVATE was still to come.
 * With no plan (lost in an app restart, or none): START only when the pump is suspended AND the loop does not set TBRs
 * itself (not closed loop / LGS: loop suspended, disabled, open loop, ...). When the loop runs, it decides at its next run
 * (B12 starts the pump if nobody does). The caller answers the queue at once when there is no START.
 */
object MedLinkCancelTbrRule {

    /**
     * @param firstIsUnsentReactivate the first plan step is a REACTIVATE not sent yet
     * @param firstIsSentSuspend the first plan step is a SUSPEND already sent
     * @param loopEnactsTbr the running mode is closed loop or LGS (`RM.Mode.isClosedLoopOrLgs`)
     */
    fun shouldStart(
        hasPlan: Boolean,
        firstIsUnsentReactivate: Boolean,
        firstIsSentSuspend: Boolean,
        pumpSuspended: Boolean,
        loopEnactsTbr: Boolean,
    ): Boolean =
        if (hasPlan) firstIsUnsentReactivate || firstIsSentSuspend || pumpSuspended
        else pumpSuspended && !loopEnactsTbr
}
