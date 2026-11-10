package app.aaps.pump.common.hw.medlink.ble

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 4r, `logs/inginiyr_m_command.log` 21:42:27-21:42:35. */
class MedLinkPostGuardTest {

    private val start = Any()
    private val m1 = Any()
    private val m2 = Any()
    private val bolus = Any()

    @Test
    fun `latest post of the current command is written`() {
        assertFalse(MedLinkPostGuard.isStale(3, 3, bolus, bolus))
    }

    @Test
    fun `posts queued behind the spacing wait are not written once replaced`() {
        // START's A post (1) is still waiting when M#1 becomes current and is posted (2): the A is not written
        assertTrue(MedLinkPostGuard.isStale(1, 2, start, m1))
        // M#1 was removed and M#2 posted (3): M#1's post is not written
        assertTrue(MedLinkPostGuard.isStale(2, 3, m1, m2))
        // the bolus is posted (4): M#2's leftover post does not write an M whose answer the bolus would take
        assertTrue(MedLinkPostGuard.isStale(3, 4, m2, bolus))
    }

    @Test
    fun `post of a command that is no longer current is not written`() {
        // the bolus was dropped (current = null): its post must not write X
        assertTrue(MedLinkPostGuard.isStale(4, 4, bolus, null))
    }

    @Test
    fun `same command posted twice is written once`() {
        assertTrue(MedLinkPostGuard.isStale(5, 6, bolus, bolus))
        assertFalse(MedLinkPostGuard.isStale(6, 6, bolus, bolus))
    }
}
