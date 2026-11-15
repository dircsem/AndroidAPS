package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkProfileReadRule.Reason
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkProfileReadRuleTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val now = 1_800_000_000_000L

    private fun read(reason: Reason, lastRequestAt: Long, lastSuccessAt: Long, busy: Boolean = false, intervalMs: Long = MedLinkProfileReadRule.INTERVAL_MS) =
        MedLinkProfileReadRule.shouldRead(now, lastRequestAt, lastSuccessAt, reason, busy, intervalMs)

    @Test
    fun `timed read - after the interval since the last good read, not before`() {
        assertFalse(read(Reason.TIMED, lastRequestAt = now - 6 * hour + min, lastSuccessAt = now - 6 * hour + min))
        assertTrue(read(Reason.TIMED, lastRequestAt = now - 6 * hour, lastSuccessAt = now - 6 * hour))
    }

    @Test
    fun `timed read - a failed read does not count as a read - tried again after the minimum gap`() {
        // the start read was requested 15 min ago but never finished (BLE drop)
        assertTrue(read(Reason.TIMED, lastRequestAt = now - 15 * min, lastSuccessAt = 0L))
        // a good read 2 h ago, a failed one 15 min ago: the interval counts from the good one
        assertFalse(read(Reason.TIMED, lastRequestAt = now - 15 * min, lastSuccessAt = now - 2 * hour))
    }

    @Test
    fun `app start - reads when no read succeeded yet in this app run`() {
        assertTrue(read(Reason.APP_START, lastRequestAt = 0L, lastSuccessAt = 0L))
    }

    @Test
    fun `app start called again (wake-up after a 5 min pause) - no new read after a good one`() {
        assertFalse(read(Reason.APP_START, lastRequestAt = now - 30 * min, lastSuccessAt = now - 30 * min))
    }

    @Test
    fun `app start called again after a failed start read - read again, but not within the minimum gap`() {
        assertFalse(read(Reason.APP_START, lastRequestAt = now - 5 * min, lastSuccessAt = 0L))
        assertTrue(read(Reason.APP_START, lastRequestAt = now - 10 * min, lastSuccessAt = 0L))
    }

    @Test
    fun `basal differs - reads after the minimum gap only`() {
        assertFalse(read(Reason.BASAL_DIFFERS, lastRequestAt = now - 5 * min, lastSuccessAt = now - 5 * min))
        assertTrue(read(Reason.BASAL_DIFFERS, lastRequestAt = now - 10 * min, lastSuccessAt = now - 10 * min))
    }

    @Test
    fun `no read while a bolus or a TBR operation runs, for every reason`() {
        Reason.entries.forEach { reason ->
            assertFalse(read(reason, lastRequestAt = now - 24 * hour, lastSuccessAt = 0L, busy = true), reason.name)
        }
    }

    @Test
    fun `no second request within a few minutes, for every reason`() {
        Reason.entries.forEach { reason ->
            assertFalse(read(reason, lastRequestAt = now - 2 * min, lastSuccessAt = 0L), reason.name)
        }
    }

    @Test
    fun `the interval can change (T19)`() {
        assertTrue(read(Reason.TIMED, lastRequestAt = now - hour, lastSuccessAt = now - hour, intervalMs = hour))
        assertFalse(read(Reason.TIMED, lastRequestAt = now - hour, lastSuccessAt = now - hour))
    }

    @Test
    fun `interval 0 - no timed read, the other reasons still read`() {
        assertFalse(read(Reason.TIMED, lastRequestAt = now - 24 * hour, lastSuccessAt = now - 24 * hour, intervalMs = 0L))
        assertTrue(read(Reason.BASAL_DIFFERS, lastRequestAt = now - 24 * hour, lastSuccessAt = now - 24 * hour, intervalMs = 0L))
    }
}
