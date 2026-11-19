package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B18 (field log 2026-11-19): the plan STOP was queued at 06:50:02, the status read at 06:50:07 still saw the pump
 * running and queued a second STOP.
 */
class MedLinkStopPendingRuleTest {

    private val now = 1_000_000_000L

    @Test
    fun `field case - STOP queued 5 s ago - pending, no second STOP`() {
        assertTrue(MedLinkStopPendingRule.isStopPending(now, now - 5_000L))
    }

    @Test
    fun `no STOP queued - not pending`() {
        assertFalse(MedLinkStopPendingRule.isStopPending(now, 0L))
    }

    @Test
    fun `STOP queued just under the limit - pending`() {
        assertTrue(MedLinkStopPendingRule.isStopPending(now, now - MedLinkStopPendingRule.MAX_WAIT_MS + 1L))
    }

    @Test
    fun `answer lost - after the limit a new STOP may be queued`() {
        assertFalse(MedLinkStopPendingRule.isStopPending(now, now - MedLinkStopPendingRule.MAX_WAIT_MS))
    }

    @Test
    fun `phone clock moved back - not pending`() {
        assertFalse(MedLinkStopPendingRule.isStopPending(now, now + 60_000L))
    }
}
