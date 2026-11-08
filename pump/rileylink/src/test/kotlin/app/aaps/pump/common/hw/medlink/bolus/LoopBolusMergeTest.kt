package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.Bolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.SMBBolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.TBRBolus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopBolusMergeTest {

    @Test
    fun `smb merges into a queued tbr bolus and tbr into a queued smb`() {
        assertTrue(LoopBolusMerge.canMerge(listOf(TBRBolus), SMBBolus))
        assertTrue(LoopBolusMerge.canMerge(listOf(SMBBolus), TBRBolus))
    }

    @Test
    fun `only one tbr and one smb per merged bolus`() {
        assertFalse(LoopBolusMerge.canMerge(listOf(TBRBolus), TBRBolus))
        assertFalse(LoopBolusMerge.canMerge(listOf(SMBBolus), SMBBolus))
        assertFalse(LoopBolusMerge.canMerge(listOf(TBRBolus, SMBBolus), SMBBolus))
        assertFalse(LoopBolusMerge.canMerge(listOf(TBRBolus, SMBBolus), TBRBolus))
    }

    @Test
    fun `a manual bolus never merges`() {
        assertFalse(LoopBolusMerge.canMerge(listOf(Bolus), SMBBolus))
        assertFalse(LoopBolusMerge.canMerge(listOf(TBRBolus), Bolus))
        assertFalse(LoopBolusMerge.canMerge(emptyList(), SMBBolus))
    }

    @Test
    fun `total is the sum sent to the pump`() {
        assertEquals(0.5, LoopBolusMerge.total(listOf(0.1, 0.4)))
        assertEquals(0.3, LoopBolusMerge.total(listOf(0.1, 0.2)))
    }

    @Test
    fun `full delivery gives each part its requested amount`() {
        assertEquals(listOf(0.1, 0.4), LoopBolusMerge.split(listOf(0.1, 0.4), 0.5))
    }

    @Test
    fun `partial delivery fills parts in queue order`() {
        assertEquals(listOf(0.1, 0.2), LoopBolusMerge.split(listOf(0.1, 0.4), 0.3))
        assertEquals(listOf(0.05, 0.0), LoopBolusMerge.split(listOf(0.1, 0.4), 0.05))
    }

    @Test
    fun `single part takes everything`() {
        assertEquals(listOf(0.7), LoopBolusMerge.split(listOf(0.7), 0.7))
    }

    @Test
    fun `parts get distinct times inside the pump minute`() {
        val pumpTime = 1_700_000_040_000L
        assertEquals(pumpTime, LoopBolusMerge.partTime(pumpTime, 0))
        assertEquals(pumpTime + 1_000L, LoopBolusMerge.partTime(pumpTime, 1))
    }
}
