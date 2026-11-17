package app.aaps.pump.medtronic.data

/**
 * B11: when the 5 s check asks for a status read. The TBR plan steps (suspend / start / TBR bolus) are started
 * inside the status refresh, so a read is needed when a step is due. But a step that is still far in the future
 * is not a reason to read (before B11 it was, and the app asked for a read every 5 s while a plan existed).
 */
object MedLinkStatusReadRule {

    /** Same margin as the step runner: it starts a step when its time is before now + 30 s. */
    const val STEP_MARGIN_MS = 30_000L

    /** A TBR plan step: its time (millis) and if its command was already sent. */
    data class Step(val releaseAt: Long, val issued: Boolean)

    /**
     * @param refreshTimes next time (millis) of each refresh type; null = not planned
     * @param steps the TBR plan steps, in order
     * @param timeChanged the phone time, date or time zone changed
     */
    fun needsRead(now: Long, refreshTimes: Collection<Long?>, steps: List<Step>, timeChanged: Boolean): Boolean {
        if (refreshTimes.any { it != null && it <= now }) return true
        if (steps.any { !it.issued && it.releaseAt < now + STEP_MARGIN_MS }) return true
        return timeChanged
    }

    /** T23: a status `S` younger than this is fresh: no planned read. */
    const val FRESH_MS = 4 * 60_000L

    /** T23: a status is never older than this: then the planned read always runs. */
    const val MAX_AGE_MS = 10 * 60_000L

    /**
     * T23: how long to wait before the planned status read (`S`). 0 = read now.
     * A STOP / START answer also gives the pump state, so no new connection only to read `S` just after it.
     * The wait never goes past [MAX_AGE_MS] after the last good `S`.
     * @param lastStatusAt last good `S` (phone time; 0 = none yet)
     * @param lastStartStopAt last confirmed STOP / START answer (phone time; 0 = none)
     */
    fun statusReadWaitMs(now: Long, lastStatusAt: Long, lastStartStopAt: Long): Long {
        if (lastStatusAt <= 0L) return 0L
        val maxAgeAt = lastStatusAt + MAX_AGE_MS
        if (now >= maxAgeAt) return 0L
        val freshUntil = minOf(maxOf(lastStatusAt, lastStartStopAt) + FRESH_MS, maxAgeAt)
        return maxOf(0L, freshUntil - now)
    }
}
