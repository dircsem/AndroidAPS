package app.aaps.pump.common.hw.medlink.ble

/**
 * 4r: a command posted to the BLE thread is written only while it is the latest post and still the current
 * command. Older posts (queued behind the spacing wait) are stale: their executor was removed or replaced.
 */
object MedLinkPostGuard {

    fun isStale(seq: Long, latestSeq: Long, executor: Any, current: Any?): Boolean =
        seq != latestSeq || executor !== current
}
