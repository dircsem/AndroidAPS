package app.aaps.pump.medtronic.data

import kotlin.math.ceil

/**
 * Pump battery shown by age (MedLink "battery age" mode): 100 % when the battery was changed, 0 % at the critical
 * battery age of the status lights (`OverviewBageCritical`). The Medtronic tab shows yellow at 25 % and red at 0 %.
 */
object MedLinkBatteryAge {

    const val WARN_PERCENT = 25
    const val URGENT_PERCENT = 0

    fun percentLeft(ageMs: Long, lifeHours: Int): Int {
        if (lifeHours <= 0) return 0
        val left = 100.0 * (1.0 - ageMs.coerceAtLeast(0L).toDouble() / (lifeHours * 3_600_000.0))
        return left.coerceIn(0.0, 100.0).toInt()
    }

    /** Font Awesome battery icon level, 0 (empty) to 4 (full): any charge left shows at least one bar. */
    fun iconLevel(percent: Int): Int = ceil(percent.coerceIn(0, 100) / 25.0).toInt()
}
