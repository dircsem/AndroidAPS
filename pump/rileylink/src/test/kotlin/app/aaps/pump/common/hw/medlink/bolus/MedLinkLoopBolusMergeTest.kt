package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.Bolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.SMBBolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.TBRBolus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkLoopBolusMergeTest {

    @Test
    fun `smb merges into a queued tbr bolus and tbr into a queued smb`() {
        assertTrue(MedLinkLoopBolusMerge.canMerge(listOf(TBRBolus), SMBBolus))
        assertTrue(MedLinkLoopBolusMerge.canMerge(listOf(SMBBolus), TBRBolus))
    }

    @Test
    fun `only one tbr and one smb per merged bolus`() {
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(TBRBolus), TBRBolus))
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(SMBBolus), SMBBolus))
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(TBRBolus, SMBBolus), SMBBolus))
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(TBRBolus, SMBBolus), TBRBolus))
    }

    @Test
    fun `a tbr merges into a queued manual bolus (T16)`() {
        assertTrue(MedLinkLoopBolusMerge.canMerge(listOf(Bolus), TBRBolus))
        assertTrue(MedLinkLoopBolusMerge.canMerge(listOf(Bolus, SMBBolus), TBRBolus))
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(Bolus, TBRBolus), TBRBolus))
    }

    @Test
    fun `an smb never merges into a manual bolus by itself`() {
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(Bolus), SMBBolus))
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(Bolus, TBRBolus), SMBBolus))
    }

    @Test
    fun `a manual bolus is never merged into another bolus`() {
        assertFalse(MedLinkLoopBolusMerge.canMerge(listOf(TBRBolus), Bolus))
        assertFalse(MedLinkLoopBolusMerge.canMerge(emptyList(), SMBBolus))
    }

    @Test
    fun `manual bolus absorbs tbr always and smb only with consent (T16)`() {
        assertTrue(MedLinkLoopBolusMerge.canAbsorbIntoManual(listOf(Bolus), TBRBolus, smbConsent = false))
        assertFalse(MedLinkLoopBolusMerge.canAbsorbIntoManual(listOf(Bolus), SMBBolus, smbConsent = false))
        assertTrue(MedLinkLoopBolusMerge.canAbsorbIntoManual(listOf(Bolus), SMBBolus, smbConsent = true))
        assertTrue(MedLinkLoopBolusMerge.canAbsorbIntoManual(listOf(Bolus, TBRBolus), SMBBolus, smbConsent = true))
        assertFalse(MedLinkLoopBolusMerge.canAbsorbIntoManual(listOf(Bolus, TBRBolus), TBRBolus, smbConsent = true))
        assertFalse(MedLinkLoopBolusMerge.canAbsorbIntoManual(listOf(Bolus), Bolus, smbConsent = true))
    }

    @Test
    fun `any other type joins the tracked bolus not received by the pump (4i)`() {
        assertTrue(MedLinkLoopBolusMerge.canJoinTracked(listOf(TBRBolus), SMBBolus, smbConsent = false))
        assertTrue(MedLinkLoopBolusMerge.canJoinTracked(listOf(SMBBolus), TBRBolus, smbConsent = false))
        assertTrue(MedLinkLoopBolusMerge.canJoinTracked(listOf(TBRBolus), Bolus, smbConsent = false))
        assertTrue(MedLinkLoopBolusMerge.canJoinTracked(listOf(Bolus), TBRBolus, smbConsent = false))
    }

    @Test
    fun `same type never joins the tracked bolus (4i)`() {
        assertFalse(MedLinkLoopBolusMerge.canJoinTracked(listOf(TBRBolus), TBRBolus, smbConsent = true))
        assertFalse(MedLinkLoopBolusMerge.canJoinTracked(listOf(TBRBolus, SMBBolus), SMBBolus, smbConsent = true))
        assertFalse(MedLinkLoopBolusMerge.canJoinTracked(listOf(Bolus), Bolus, smbConsent = true))
        assertFalse(MedLinkLoopBolusMerge.canJoinTracked(emptyList(), SMBBolus, smbConsent = true))
    }

    @Test
    fun `smb and manual join only with the user's consent (4i, T16)`() {
        assertFalse(MedLinkLoopBolusMerge.canJoinTracked(listOf(SMBBolus), Bolus, smbConsent = false))
        assertTrue(MedLinkLoopBolusMerge.canJoinTracked(listOf(SMBBolus), Bolus, smbConsent = true))
        assertFalse(MedLinkLoopBolusMerge.canJoinTracked(listOf(TBRBolus, Bolus), SMBBolus, smbConsent = false))
        assertTrue(MedLinkLoopBolusMerge.canJoinTracked(listOf(TBRBolus, Bolus), SMBBolus, smbConsent = true))
    }

    @Test
    fun `total is the sum sent to the pump`() {
        assertEquals(0.5, MedLinkLoopBolusMerge.total(listOf(0.1, 0.4)))
        assertEquals(0.3, MedLinkLoopBolusMerge.total(listOf(0.1, 0.2)))
    }

    @Test
    fun `full delivery gives each part its requested amount`() {
        assertEquals(listOf(0.1, 0.4), MedLinkLoopBolusMerge.split(listOf(0.1, 0.4), 0.5))
    }

    @Test
    fun `partial delivery fills parts in queue order`() {
        assertEquals(listOf(0.1, 0.2), MedLinkLoopBolusMerge.split(listOf(0.1, 0.4), 0.3))
        assertEquals(listOf(0.05, 0.0), MedLinkLoopBolusMerge.split(listOf(0.1, 0.4), 0.05))
    }

    @Test
    fun `single part takes everything`() {
        assertEquals(listOf(0.7), MedLinkLoopBolusMerge.split(listOf(0.7), 0.7))
    }

    @Test
    fun `parts get distinct times inside the pump minute`() {
        val pumpTime = 1_700_000_040_000L
        assertEquals(pumpTime, MedLinkLoopBolusMerge.partTime(pumpTime, 0))
        assertEquals(pumpTime + 1_000L, MedLinkLoopBolusMerge.partTime(pumpTime, 1))
    }
}
