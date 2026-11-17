package app.aaps.pump.common.hw.medlink.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B10 (field log 2026-11-14): after a FRESH MedLink + pump wake-up, the first command written about 0.8 s after
 * "ready" was echoed by the MedLink but never answered by the pump (12 of 12), and was sent again 10 s later.
 */
class MedLinkFreshWakeTest {

    /** The "ready" answer of a fresh wake-up, as in logs/AndroidAPS.log 16:43:12. */
    private val freshBanner = listOf(
        "med-link wake up by bluetooth", "firmware version 01_05_2022", "med-link battery:  64%", "wait for respons",
        "send command for pump wakeup", "confirmed pump wakeup", "medtronic veo", "ready"
    ).joinToString("\n")

    @Test
    fun `the fresh wake-up banner is found`() {
        assertTrue(MedLinkFreshWake.isFreshWake(freshBanner))
    }

    @Test
    fun `a ready of a MedLink that was already awake is not a fresh wake-up`() {
        assertFalse(MedLinkFreshWake.isFreshWake("powerdown after timeout\nready"))
        assertFalse(MedLinkFreshWake.isFreshWake("set pump state timeout 10s\nready"))
        assertFalse(MedLinkFreshWake.isFreshWake("ready"))
    }

    @Test
    fun `the first command waits until the settle time after the fresh ready`() {
        val readyAt = 1_000_000L
        assertEquals(MedLinkFreshWake.SETTLE_MS, MedLinkFreshWake.holdMs(now = readyAt, freshReadyAt = readyAt))
        assertEquals(MedLinkFreshWake.SETTLE_MS - 800, MedLinkFreshWake.holdMs(now = readyAt + 800, freshReadyAt = readyAt))
        assertEquals(0L, MedLinkFreshWake.holdMs(now = readyAt + MedLinkFreshWake.SETTLE_MS, freshReadyAt = readyAt))
    }

    @Test
    fun `no fresh wake-up - no wait`() {
        assertEquals(0L, MedLinkFreshWake.holdMs(now = 5_000L, freshReadyAt = 0L))
    }
}
