package app.aaps.ui.compose.overview

import java.time.Instant
import java.time.ZoneId

/**
 * MedLink: the past part of the basal graph shows the insulin really delivered, in blocks of the setting
 * "Main screen TBR: minutes" (T31; it was a fixed 30 min aligned to UTC). MedLink makes a temp basal with TBR
 * microboluses (more than 100 %) and suspensions (less than 100 %), so a line of the temp basal rate alone is wrong.
 *
 * Rate of a block = (basal line insulin, suspensions included + TBR microboluses) / block length.
 * Blocks follow each other (no overlap) and start at local :00: block n of a day starts at local midnight +
 * n x [blockMinutes] (wall clock, so on a daylight saving day one block can be shorter or longer). The first block
 * (start of the graph) and the last one (now) can be shorter.
 */
object MedLinkBasalBlocks {

    /**
     * @param minuteRates one (time, rate in U/h) per minute, in time order
     * @param tbrBoluses (time, units) of each MedLink TBR microbolus
     * @return (block start, delivered rate in U/h) for each block
     */
    fun blocks(minuteRates: List<Pair<Long, Double>>, tbrBoluses: List<Pair<Long, Double>>, blockMinutes: Int, zone: ZoneId): List<Pair<Long, Double>> {
        if (minuteRates.isEmpty() || blockMinutes <= 0) return emptyList()
        return minuteRates.groupBy { blockKey(it.first, blockMinutes, zone) }.values.map { minutes ->
            val start = minutes.first().first
            val end = minutes.last().first + 60_000L
            val basalUnits = minutes.sumOf { it.second } / 60.0
            val bolusUnits = tbrBoluses.filter { it.first in start until end }.sumOf { it.second }
            start to (basalUnits + bolusUnits) * 60.0 / minutes.size
        }
    }

    /** The local day and the block number in it. */
    private fun blockKey(time: Long, blockMinutes: Int, zone: ZoneId): Pair<Long, Int> {
        val local = Instant.ofEpochMilli(time).atZone(zone).toLocalDateTime()
        val minuteOfDay = local.hour * 60 + local.minute
        return local.toLocalDate().toEpochDay() to minuteOfDay / blockMinutes
    }
}
