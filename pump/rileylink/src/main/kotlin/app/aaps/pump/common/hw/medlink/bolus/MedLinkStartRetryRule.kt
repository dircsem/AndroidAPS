package app.aaps.pump.common.hw.medlink.bolus

/**
 * T13 / bug 4: the pump stayed suspended after START (refused - e.g. a message on the pump screen -
 * or the connection kept failing). Re-sending START is safe: it never delivers insulin, and the `A`
 * step of the next attempt finishes at once when the pump already runs.
 * Staying suspended is the risk here (no basal), so retry with a growing delay and alarm early.
 */
object MedLinkStartRetryRule {

    /** Failures before giving up (with alarm). */
    const val MAX_FAILURES = 5

    private val DELAYS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

    sealed class Decision {
        data class Retry(val delayMs: Long, val alarm: Boolean) : Decision()
        object GiveUp : Decision() {
            override fun toString() = "GiveUp"
        }
    }

    /** @param failures failed START attempts in a row, counting this one (1-based). */
    fun onFailed(failures: Int): Decision =
        if (failures >= MAX_FAILURES) Decision.GiveUp
        else Decision.Retry(DELAYS_MS[(failures - 1).coerceIn(0, DELAYS_MS.size - 1)], alarm = failures >= 2)

    /** A scheduled retry is dropped when a newer start/stop was sent or the pump already answered Running. */
    fun shouldStillRetry(failedAt: Long, lastStartStopSent: Long, pumpRunningConfirmed: Boolean): Boolean =
        !pumpRunningConfirmed && lastStartStopSent <= failedAt
}
