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
}
