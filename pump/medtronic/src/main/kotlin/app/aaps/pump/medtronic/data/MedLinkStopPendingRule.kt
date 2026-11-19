package app.aaps.pump.medtronic.data

/**
 * B18 (field log 2026-11-19 06:50): the plan queued a STOP at 06:50:02. A status read came at 06:50:07, before the
 * STOP was sent, and still said "pump normal". `handlePumpStatus` saw a 0 % TBR with a running pump and queued a
 * second STOP. Not dangerous (no insulin), but one more pump wake-up and more battery use.
 *
 * A "safety" STOP (the pump runs while the TBR is 0 %) is not queued while another STOP waits for its answer.
 * The STOP of a plan step is always queued: skipping it could lose the step.
 */
object MedLinkStopPendingRule {

    /**
     * A STOP queued this long ago without an answer does not count any more: the answer was lost, so a new safety
     * STOP may be queued. In the field log the STOP waited 20 s; the MedLink queue can hold it longer.
     */
    const val MAX_WAIT_MS = 3 * 60_000L

    /**
     * @param stopQueuedAt phone time when a STOP was queued and its answer did not come yet; 0 = none
     * @return true when a STOP is still on its way: do not queue another one
     */
    fun isStopPending(now: Long, stopQueuedAt: Long): Boolean =
        stopQueuedAt > 0L && now >= stopQueuedAt && now - stopQueuedAt < MAX_WAIT_MS
}
