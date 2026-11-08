package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManualBolusRuleTest {

    @Test
    fun `manual bolus is queued behind an unresolved tbr bolus`() {
        assertTrue(ManualBolusRule.mayQueueBehind(MedLinkCommandType.TBRBolus))
    }

    @Test
    fun `manual bolus is still refused behind an unresolved smb or manual bolus`() {
        assertFalse(ManualBolusRule.mayQueueBehind(MedLinkCommandType.SMBBolus))
        assertFalse(ManualBolusRule.mayQueueBehind(MedLinkCommandType.Bolus))
        assertFalse(ManualBolusRule.mayQueueBehind(null))
    }

    @Test
    fun `manual bolus is queued behind an unresolved smb only with consent (T16)`() {
        assertTrue(ManualBolusRule.mayQueueBehind(MedLinkCommandType.SMBBolus, smbConsent = true))
        assertFalse(ManualBolusRule.mayQueueBehind(MedLinkCommandType.SMBBolus, smbConsent = false))
        assertFalse(ManualBolusRule.mayQueueBehind(MedLinkCommandType.Bolus, smbConsent = true))
    }

    @Test
    fun `manual bolus expires after the max wait`() {
        val since = 1_000_000L
        assertFalse(ManualBolusRule.expired(since, since + ManualBolusRule.MAX_WAIT_MS))
        assertTrue(ManualBolusRule.expired(since, since + ManualBolusRule.MAX_WAIT_MS + 1))
    }
}
