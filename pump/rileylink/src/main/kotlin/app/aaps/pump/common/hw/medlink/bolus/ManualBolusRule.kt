package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType

/**
 * T15: a manual bolus is never refused because of a TBR microbolus.
 *
 *  - while a TBR bolus is unresolved, a manual bolus is queued instead of refused; MedLinkBLE sends it
 *    as soon as the TBR is resolved (one bolus in flight, M decides, as before);
 *  - an unresolved SMB or manual bolus still refuses it (the SMB / manual restriction stays);
 *  - a queued manual bolus goes before loop boluses that were not posted yet;
 *  - a manual bolus waiting longer than [MAX_WAIT_MS] is dropped with an alarm: it must not be
 *    delivered long after the user asked for it.
 */
object ManualBolusRule {

    const val MAX_WAIT_MS = 5 * 60_000L

    /** May a manual bolus be queued while the bolus of [unresolvedType] is not resolved? */
    fun mayQueueBehind(unresolvedType: MedLinkCommandType?): Boolean = unresolvedType == MedLinkCommandType.TBRBolus

    fun expired(waitingSince: Long, now: Long): Boolean = now - waitingSince > MAX_WAIT_MS
}
