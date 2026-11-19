package app.aaps.pump.common.hw.medlink.connection

/**
 * T25: one OK+CONN per connection. After a Bluetooth connect the MedLink prints a banner by itself
 * ("med-link wake up by bluetooth, version …, med-link battery: 30% ok+conn or command"). An OK+CONN written before
 * the banner is lost (field log 2026-11-17 08:20:16: written at .921, banner at 17.795), so a second OK+CONN was
 * needed. The first OK+CONN now waits for the banner, at most [BANNER_WAIT_MS]; then it is written anyway (as before).
 * After the banner it waits [AFTER_BANNER_MS] more (field log 2026-11-18: written 30 ms after the banner, "invalid
 * command" 10 of 14; before T25 it went out 1.15 s after the banner, 16 of 16 OK).
 */
object MedLinkConnectBannerRule {

    /** The banner comes about 1 s after the connect; with no banner in this time the OK+CONN is written anyway. */
    const val BANNER_WAIT_MS = 3_000L

    /** Wait after the banner end before the OK+CONN (the old working time was 1.15 s). */
    const val AFTER_BANNER_MS = 1_200L

    private const val BANNER_END = "ok+conn or command"

    /** The end of the banner, also when it is split over two Bluetooth chunks. */
    fun isBannerEnd(lastCharacteristic: String, answer: String): Boolean =
        (lastCharacteristic + answer).contains(BANNER_END) && !lastCharacteristic.contains(BANNER_END)

    /**
     * @param gattReadyAt when the services of this connection were found; 0 = unknown
     * @param bannerAt when the last banner ended; 0 = none
     * @return how long the first OK+CONN must still wait; 0 = write now
     */
    fun holdMs(now: Long, gattReadyAt: Long, bannerAt: Long): Long {
        if (gattReadyAt <= 0L) return 0L
        if (bannerAt >= gattReadyAt) return maxOf(0L, bannerAt + AFTER_BANNER_MS - now)
        return maxOf(0L, gattReadyAt + BANNER_WAIT_MS - now)
    }
}
