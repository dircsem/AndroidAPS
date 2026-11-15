package app.aaps.pump.medtronic.data

/**
 * B6: when the MedLink reads the pump profile (E + F). Each read keeps the MedLink busy for a while, so:
 * never during a bolus or a TBR operation (the caller tries again at the next status read), and never two requests
 * within [MIN_GAP_MS]. A request that never finished (BLE drop) does not count as a read: the intervals count from the
 * last GOOD read, so a failed read is tried again at the next status read after [MIN_GAP_MS].
 */
object MedLinkProfileReadRule {

    enum class Reason {

        /** App start (`postInit`). It is also called again after a MedLink wake-up: then it reads only if no read succeeded yet. */
        APP_START,

        /** Every [INTERVAL_MS] (T19 will make it a setting; 0 = no timed read). */
        TIMED,

        /** The basal in the status `S` is not the one of the last read profile. */
        BASAL_DIFFERS,

        /** The status parser saw a line it does not know. */
        UNKNOWN_STATUS_LINE,

        /** The user opened the Profiles screen. */
        SCREEN_OPENED
    }

    const val INTERVAL_MS = 6 * 60 * 60_000L
    const val MIN_GAP_MS = 10 * 60_000L

    /**
     * @param lastRequestAt when the last read was requested (0 = never): only for the minimum gap
     * @param lastSuccessAt when the last read finished with a parsed profile (0 = not yet in this app run)
     * @param busy a bolus is in flight or a TBR operation is due soon
     * @param intervalMs interval of the timed read; 0 = no timed read
     */
    fun shouldRead(now: Long, lastRequestAt: Long, lastSuccessAt: Long, reason: Reason, busy: Boolean, intervalMs: Long = INTERVAL_MS): Boolean {
        if (busy) return false
        if (lastRequestAt > 0 && now - lastRequestAt < MIN_GAP_MS) return false
        return when (reason) {
            Reason.APP_START -> lastSuccessAt == 0L
            Reason.TIMED -> intervalMs > 0 && (lastSuccessAt == 0L || now - lastSuccessAt >= intervalMs)
            Reason.BASAL_DIFFERS, Reason.UNKNOWN_STATUS_LINE, Reason.SCREEN_OPENED -> true
        }
    }
}
