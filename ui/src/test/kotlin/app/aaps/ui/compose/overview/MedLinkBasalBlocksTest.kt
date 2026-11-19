package app.aaps.ui.compose.overview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T31: the MedLink basal graph uses the "Main screen TBR: minutes" setting for its blocks (it was a fixed 30 min,
 * aligned to UTC). Blocks follow each other, start at local :00, and give the insulin really delivered as a rate.
 */
class MedLinkBasalBlocksTest {

    private val saoPaulo = ZoneId.of("America/Sao_Paulo")
    private val india = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val minute = 60_000L

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** One rate per minute from [from] for [minutes] minutes. */
    private fun minutes(from: Long, minutes: Int, rate: Double = 1.0) = (0 until minutes).map { from + it * minute to rate }

    @Test
    fun `30 min blocks start at local 00 and 30`() {
        val start = at(saoPaulo, 2026, 11, 17, 8, 0)
        val blocks = MedLinkBasalBlocks.blocks(minutes(start, 90), emptyList(), 30, saoPaulo)
        assertThat(blocks.map { it.first }).containsExactly(start, start + 30 * minute, start + 60 * minute).inOrder()
        blocks.forEach { assertThat(it.second).isWithin(1e-9).of(1.0) }
    }

    @Test
    fun `15 and 60 min blocks`() {
        val start = at(saoPaulo, 2026, 11, 17, 8, 0)
        assertThat(MedLinkBasalBlocks.blocks(minutes(start, 120), emptyList(), 15, saoPaulo)).hasSize(8)
        assertThat(MedLinkBasalBlocks.blocks(minutes(start, 120), emptyList(), 60, saoPaulo)).hasSize(2)
    }

    @Test
    fun `45 min blocks (not an allowed value, the math still works) start from local midnight`() {
        val start = at(saoPaulo, 2026, 11, 17, 0, 0)
        val blocks = MedLinkBasalBlocks.blocks(minutes(start, 90), emptyList(), 45, saoPaulo)
        assertThat(blocks.map { it.first }).containsExactly(start, start + 45 * minute).inOrder()
    }

    @Test
    fun `TBR microboluses - more than the basal`() {
        val start = at(saoPaulo, 2026, 11, 17, 8, 0)
        // 30 min at 1 U/h = 0.5 U, plus 0.25 U of TBR boluses = 0.75 U in 30 min = 1.5 U/h
        val boluses = listOf(start + 5 * minute to 0.1, start + 20 * minute to 0.15, start + 30 * minute to 0.5)
        val blocks = MedLinkBasalBlocks.blocks(minutes(start, 30), boluses, 30, saoPaulo)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].second).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `a suspension - less than the basal`() {
        val start = at(saoPaulo, 2026, 11, 17, 8, 0)
        val rates = minutes(start, 20, 1.0) + minutes(start + 20 * minute, 10, 0.0)
        val blocks = MedLinkBasalBlocks.blocks(rates, emptyList(), 30, saoPaulo)
        assertThat(blocks[0].second).isWithin(1e-9).of(20.0 / 30.0)
    }

    @Test
    fun `short first block (start of the graph) and short last block (now)`() {
        val start = at(saoPaulo, 2026, 11, 17, 8, 10)
        val blocks = MedLinkBasalBlocks.blocks(minutes(start, 35), emptyList(), 30, saoPaulo) // 08:10 .. 08:45
        assertThat(blocks.map { it.first }).containsExactly(start, at(saoPaulo, 2026, 11, 17, 8, 30)).inOrder()
        blocks.forEach { assertThat(it.second).isWithin(1e-9).of(1.0) }
    }

    @Test
    fun `the last block has the same rate as the delivered basal of the same minutes`() {
        // MedLinkDeliveredBasal: (basal insulin + TBR boluses) / minutes, as a rate
        val start = at(saoPaulo, 2026, 11, 17, 9, 0)
        val rates = minutes(start, 10, 0.75) + minutes(start + 10 * minute, 5, 0.0)
        val boluses = listOf(start + 2 * minute to 0.1)
        val expected = ((10 * 0.75) / 60.0 + 0.1) * 60.0 / 15.0
        assertThat(MedLinkBasalBlocks.blocks(rates, boluses, 15, saoPaulo).last().second).isWithin(1e-9).of(expected)
    }

    @Test
    fun `time zone with a 30 min offset - blocks start at local 00, not UTC`() {
        val start = at(india, 2026, 11, 17, 8, 0)
        val blocks = MedLinkBasalBlocks.blocks(minutes(start, 120), emptyList(), 60, india)
        assertThat(blocks.map { it.first }).containsExactly(start, at(india, 2026, 11, 17, 9, 0)).inOrder()
    }

    @Test
    fun `daylight saving day - blocks still start at local 00`() {
        // Berlin 2026-03-29: 02:00 -> 03:00
        val start = at(berlin, 2026, 3, 29, 1, 0)
        val blocks = MedLinkBasalBlocks.blocks(minutes(start, 120), emptyList(), 60, berlin)
        assertThat(blocks.map { it.first }).containsExactly(start, at(berlin, 2026, 3, 29, 3, 0)).inOrder()
    }

    @Test
    fun `no minutes - no blocks`() {
        assertThat(MedLinkBasalBlocks.blocks(emptyList(), emptyList(), 30, saoPaulo)).isEmpty()
    }
}
