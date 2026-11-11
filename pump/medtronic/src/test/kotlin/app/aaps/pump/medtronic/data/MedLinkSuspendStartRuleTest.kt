package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MedLinkSuspendStartRuleTest {

    private val min = 60_000L
    private val cycleStart = 1_800_000_000L * min // a cycle boundary of every cycle length used here (multiple of 10, 20, 30)

    private fun left(minute: Long, suspend: Int, active: Int) = MedLinkSuspendStartRule.activeMinutesLeft(cycleStart + minute * min, suspend, active)

    @Test
    fun `50 percent - suspended the first half of the cycle, active the second half`() {
        // cycle 10 min: 5 suspended, 5 active
        assertEquals(0, left(0, 5, 5))
        assertEquals(0, left(4, 5, 5))
        assertEquals(5, left(5, 5, 5))
        assertEquals(1, left(9, 5, 5))
        assertEquals(0, left(10, 5, 5))
    }

    @Test
    fun `planning again in the same minute gives the same start`() {
        val now = cycleStart + 7 * min
        val first = MedLinkSuspendStartRule.activeMinutesLeft(now, 5, 5)
        repeat(10) { assertEquals(first, MedLinkSuspendStartRule.activeMinutesLeft(now + 30_000, 5, 5)) }
    }

    @Test
    fun `75 percent - active three quarters of the time`() {
        // cycle 20 min: 5 suspended, 15 active
        val activeMinutes = (0 until 20L).count { left(it, 5, 15) > 0 }
        assertEquals(15, activeMinutes)
    }

    @Test
    fun `25 percent - active a quarter of the time`() {
        // cycle 20 min: 15 suspended, 5 active
        assertEquals(5, (0 until 20L).count { left(it, 15, 5) > 0 })
    }

    @Test
    fun `no active time - always suspended, no suspension - always active`() {
        assertEquals(0, left(3, 30, 0))
        assertEquals(5, left(3, 0, 5))
    }
}
