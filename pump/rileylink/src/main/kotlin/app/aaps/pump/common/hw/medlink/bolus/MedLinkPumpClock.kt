package app.aaps.pump.common.hw.medlink.bolus

import java.util.Calendar

/**
 * The MedLink does not always send the right year: the same pump bolus came as `09-11-00 11:03` in one
 * `M` and `09-11-26 11:29` in another. With year 2000 a bolus looks 26 years old (or a baseline looks newer
 * than the answer), so `M` "cannot tell last bolus from the sent one". The pump year is ignored when it is
 * clearly wrong: the date is moved to the year that puts it nearest to the phone time of the read.
 */
object MedLinkPumpClock {

    private const val MAX_TRUSTED_DISTANCE_MS = 180L * 24 * 60 * 60 * 1000

    fun normalizeYear(pumpTime: Long, readAt: Long): Long {
        if (kotlin.math.abs(pumpTime - readAt) <= MAX_TRUSTED_DISTANCE_MS) return pumpTime
        val read = Calendar.getInstance().apply { timeInMillis = readAt }
        val year = read.get(Calendar.YEAR)
        return listOf(year - 1, year, year + 1)
            .map { y -> Calendar.getInstance().apply { timeInMillis = pumpTime; set(Calendar.YEAR, y) }.timeInMillis }
            .minByOrNull { kotlin.math.abs(it - readAt) }!!
    }
}
