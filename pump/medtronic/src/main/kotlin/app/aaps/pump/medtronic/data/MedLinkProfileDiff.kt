package app.aaps.pump.medtronic.data

import kotlin.math.abs

/**
 * 4v: where the basal profile on the pump differs from the AAPS profile (MedLink cannot write it to the Veo, so the
 * user changes it on the pump). Hours in a row with the same pair of rates are one range.
 */
object MedLinkProfileDiff {

    /** [fromHour] inclusive, [toHour] exclusive (24 = midnight). */
    data class Range(val fromHour: Int, val toHour: Int, val pump: Double, val aaps: Double)

    private const val TOLERANCE = 0.000001

    fun differences(pumpByHour: DoubleArray, aapsByHour: List<Double>): List<Range> {
        val hours = minOf(24, pumpByHour.size, aapsByHour.size)
        val ranges = mutableListOf<Range>()
        for (h in 0 until hours) {
            val pump = pumpByHour[h]
            val aaps = aapsByHour[h]
            if (abs(pump - aaps) <= TOLERANCE) continue
            val last = ranges.lastOrNull()
            if (last != null && last.toHour == h && abs(last.pump - pump) <= TOLERANCE && abs(last.aaps - aaps) <= TOLERANCE)
                ranges[ranges.lastIndex] = last.copy(toHour = h + 1)
            else ranges += Range(h, h + 1, pump, aaps)
        }
        return ranges
    }

    fun hour(h: Int): String = String.format(java.util.Locale.ROOT, "%02d:00", h % 24)
}
