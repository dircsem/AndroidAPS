package app.aaps.pump.medtronic.data

/**
 * 4x: temp basals below 100% are suspensions spread over the duration, and the loop plans again every few minutes.
 * Every plan started suspended, so a 50% temp basal re-planned every 5 min suspended more than half of the time.
 *
 * The start follows the clock, not how many times a plan was made: time is cut in cycles of one suspension
 * ([suspendMinutes]) followed by one active interval ([activeMinutes]), aligned to the clock. A plan made during the
 * active part starts active for the minutes left of it; during the suspended part it starts suspended. Planning again
 * at any moment gives the same answer, and over time the pump runs [activeMinutes] of every cycle.
 */
object MedLinkSuspendStartRule {

    private const val MINUTE = 60_000L

    /** @return minutes to stay active before the first suspension; 0 = start suspended now. */
    fun activeMinutesLeft(now: Long, suspendMinutes: Int, activeMinutes: Int): Int {
        if (activeMinutes <= 0) return 0
        if (suspendMinutes <= 0) return activeMinutes
        val cycle = (suspendMinutes + activeMinutes).toLong()
        val phase = Math.floorMod(now / MINUTE, cycle)
        return if (phase < suspendMinutes) 0 else (cycle - phase).toInt()
    }
}
