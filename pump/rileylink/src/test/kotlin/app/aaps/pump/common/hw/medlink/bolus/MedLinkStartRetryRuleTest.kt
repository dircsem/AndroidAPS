package app.aaps.pump.common.hw.medlink.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkStartRetryRuleTest {

    @Test
    fun `first refusal retries after 30 s without alarm`() {
        val d = MedLinkStartRetryRule.onFailed(1) as MedLinkStartRetryRule.Decision.Retry
        assertEquals(30_000L, d.delayMs)
        assertFalse(d.alarm)
    }

    @Test
    fun `second refusal retries and alarms (a message on the pump screen blocks START)`() {
        val d = MedLinkStartRetryRule.onFailed(2) as MedLinkStartRetryRule.Decision.Retry
        assertEquals(60_000L, d.delayMs)
        assertTrue(d.alarm)
    }

    @Test
    fun `delays grow and never shrink`() {
        val delays = (1 until MedLinkStartRetryRule.MAX_FAILURES).map { (MedLinkStartRetryRule.onFailed(it) as MedLinkStartRetryRule.Decision.Retry).delayMs }
        assertEquals(delays.sorted(), delays)
    }

    @Test
    fun `gives up with alarm after MAX_FAILURES`() {
        assertEquals(MedLinkStartRetryRule.Decision.GiveUp, MedLinkStartRetryRule.onFailed(MedLinkStartRetryRule.MAX_FAILURES))
        assertEquals(MedLinkStartRetryRule.Decision.GiveUp, MedLinkStartRetryRule.onFailed(MedLinkStartRetryRule.MAX_FAILURES + 3))
    }

    @Test
    fun `retry is cancelled when another start or stop was sent after the failure`() {
        assertFalse(MedLinkStartRetryRule.shouldStillRetry(failedAt = 1_000, lastStartStopSent = 2_000, pumpRunningConfirmed = false))
    }

    @Test
    fun `retry is cancelled when the pump already answered Running`() {
        assertFalse(MedLinkStartRetryRule.shouldStillRetry(failedAt = 1_000, lastStartStopSent = 500, pumpRunningConfirmed = true))
    }

    @Test
    fun `retry goes on when nothing changed since the failure`() {
        assertTrue(MedLinkStartRetryRule.shouldStillRetry(failedAt = 1_000, lastStartStopSent = 500, pumpRunningConfirmed = false))
    }
}
