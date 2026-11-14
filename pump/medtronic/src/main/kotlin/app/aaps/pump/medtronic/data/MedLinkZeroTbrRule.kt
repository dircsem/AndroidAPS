package app.aaps.pump.medtronic.data

/**
 * T21: a 0 % temp basal in AAPS when the pump battery is old (age status red) and the pump cannot be reached.
 * A dead battery leaves no event in the pump history, so on reconnect the user decides if the pump stopped
 * ("Yes": the record stays) or ran ("No": the record is removed). Plain rule, unit tested.
 */
object MedLinkZeroTbrRule {

    /**
     * @param batteryInfoIsAge MedLink setting "battery info" = age
     * @param batteryAgeHours hours since the last `PUMP_BATTERY_CHANGE`; null = no event (age unknown)
     * @param criticalHours `OverviewBageCritical`
     * @param failures failed pump connections in a row (T20 counter)
     * @param threshold T20 setting "failed connections before the alarm" (the same number, user 2026-11-14)
     * @param alreadyStarted a 0 % record is already written for this outage
     * @param alarmEnabled T20 setting "alarm when the pump is not reached" (the counter is only used when it is on)
     * @param disconnectedOnPurpose AAPS running mode "disconnect pump"
     */
    fun shouldStart(
        batteryInfoIsAge: Boolean,
        batteryAgeHours: Long?,
        criticalHours: Int,
        failures: Int,
        threshold: Int,
        alreadyStarted: Boolean,
        alarmEnabled: Boolean,
        disconnectedOnPurpose: Boolean
    ): Boolean =
        batteryInfoIsAge && alarmEnabled && !disconnectedOnPurpose && !alreadyStarted &&
            batteryAgeHours != null && batteryAgeHours >= criticalHours &&
            failures >= threshold.coerceAtLeast(1)

    enum class PumpRan { PROBABLY_YES, PROBABLY_NO, UNKNOWN }

    /** Below this scheduled basal of the outage the reservoir (0.1 U steps) cannot tell anything. */
    private const val MIN_SCHEDULED_U = 0.5

    /**
     * A hint for the user, from the reservoir before and after the outage and the scheduled basal of that time.
     * The user decides; this is only one simple sentence in the question.
     */
    fun pumpRan(reservoirBefore: Double?, reservoirNow: Double?, scheduledUnits: Double): PumpRan {
        if (reservoirBefore == null || reservoirNow == null || scheduledUnits < MIN_SCHEDULED_U) return PumpRan.UNKNOWN
        val used = reservoirBefore - reservoirNow
        return when {
            used < -0.1                         -> PumpRan.UNKNOWN // went up: refilled
            used >= 0.75 * scheduledUnits       -> PumpRan.PROBABLY_YES
            used <= 0.15 * scheduledUnits + 0.1 -> PumpRan.PROBABLY_NO
            else                                -> PumpRan.UNKNOWN
        }
    }
}
