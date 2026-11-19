package app.aaps.pump.common.hw.medlink.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T25 (field log 2026-11-17 08:20:16): the first OK+CONN was written 0.9 s before the MedLink banner and was lost;
 * a second Connect executor then wrote the OK+CONN that worked. Now the first OK+CONN waits for the banner.
 */
class MedLinkConnectBannerRuleTest {

    private val ready = 1_000_000L

    @Test
    fun `banner end - in one chunk or split over two`() {
        assertTrue(MedLinkConnectBannerRule.isBannerEnd("0%", "e ok+conn or command"))
        assertTrue(MedLinkConnectBannerRule.isBannerEnd("battery: 30% ok+conn ", "or command"))
        assertFalse(MedLinkConnectBannerRule.isBannerEnd("", "ok+conn command confirmed"))
        assertFalse(MedLinkConnectBannerRule.isBannerEnd("", "med-link wake up by"))
    }

    @Test
    fun `fresh connection, no banner yet - wait`() {
        assertEquals(3_000L, MedLinkConnectBannerRule.holdMs(now = ready, gattReadyAt = ready, bannerAt = 0L))
        assertEquals(2_100L, MedLinkConnectBannerRule.holdMs(now = ready + 900, gattReadyAt = ready, bannerAt = 0L))
    }

    /**
     * Field log 2026-11-18: OK+CONN written 30 ms after the banner end: "invalid command" 10 of 14. Before T25 it went
     * out 1.15 s after the banner: 16 of 16 OK. So it waits [MedLinkConnectBannerRule.AFTER_BANNER_MS] after the banner.
     */
    @Test
    fun `banner came after this connection - wait a little after the banner`() {
        val banner = ready + 880
        assertEquals(1_200L, MedLinkConnectBannerRule.AFTER_BANNER_MS)
        assertEquals(1_200L, MedLinkConnectBannerRule.holdMs(now = banner, gattReadyAt = ready, bannerAt = banner))
        assertEquals(1_170L, MedLinkConnectBannerRule.holdMs(now = banner + 30, gattReadyAt = ready, bannerAt = banner))
        assertEquals(0L, MedLinkConnectBannerRule.holdMs(now = banner + 1_200, gattReadyAt = ready, bannerAt = banner))
        assertEquals(0L, MedLinkConnectBannerRule.holdMs(now = banner + 60_000, gattReadyAt = ready, bannerAt = banner))
    }

    @Test
    fun `banner came before the 3 s end - the wait after the banner counts, not the 3 s`() {
        // banner at 2.9 s: the OK+CONN goes at 4.1 s, not at 3 s
        val banner = ready + 2_900
        assertEquals(1_100L, MedLinkConnectBannerRule.holdMs(now = ready + 3_000, gattReadyAt = ready, bannerAt = banner))
    }

    @Test
    fun `a banner of an older connection does not count`() {
        assertEquals(2_000L, MedLinkConnectBannerRule.holdMs(now = ready + 1_000, gattReadyAt = ready, bannerAt = ready - 60_000))
    }

    @Test
    fun `no banner in 3 s - write anyway, as before`() {
        assertEquals(0L, MedLinkConnectBannerRule.holdMs(now = ready + 3_000, gattReadyAt = ready, bannerAt = 0L))
        assertEquals(0L, MedLinkConnectBannerRule.holdMs(now = ready + 60_000, gattReadyAt = ready, bannerAt = 0L))
    }

    @Test
    fun `no connection time known - no wait`() {
        assertEquals(0L, MedLinkConnectBannerRule.holdMs(now = ready, gattReadyAt = 0L, bannerAt = 0L))
    }
}
