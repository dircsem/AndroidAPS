package app.aaps.pump.common.hw.medlink.ble

import kotlin.math.max

/**
 * B10: right after a FRESH MedLink + pump wake-up ("med-link wake up by bluetooth ... medtronic veo / ready"), a command
 * written about 0.8 s later was echoed by the MedLink but never answered by the pump (field log 2026-11-14: 12 of 12),
 * and the MedLink sent it again only after its 10 s timeout. A "ready" of a MedLink that was already awake was fine.
 * So after a fresh wake-up the first command waits [SETTLE_MS]. Nothing is sent twice: it only waits.
 */
object MedLinkFreshWake {

    /** Wait after a fresh wake-up "ready" before the first command (to be checked in the field log). */
    const val SETTLE_MS = 2_000L

    fun isFreshWake(readyAnswer: String): Boolean = readyAnswer.contains("med-link wake up by bluetooth")

    /** @param freshReadyAt when the fresh wake-up "ready" came (0 = none) @return how long the queue still waits */
    fun holdMs(now: Long, freshReadyAt: Long): Long = if (freshReadyAt == 0L) 0L else max(0L, freshReadyAt + SETTLE_MS - now)
}
