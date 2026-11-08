package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.bolus.BolusOutcome.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Root cause 7: `delivered == 0` must not by itself mean "bolus completed". */
class BolusProgressRuleTest {

    private val min = 60_000L
    private val prevTime = Fixtures.pumpTime("10-06-21 17:30")
    private val newTime = Fixtures.pumpTime("10-06-21 17:45")
    private val createdAt = newTime
    private val later = createdAt + 10 * min

    private fun decide(s: BolusStatusSnapshot?, baselineTime: Long? = prevTime, baselineAmount: Double? = 0.5) =
        BolusProgressRule.decide(s, requested = 0.5, baselineTime = baselineTime, baselineAmount = baselineAmount, createdAt = createdAt, now = later).type

    @Test
    fun `square 0 and last bolus is the old one - not started, not recorded`() {
        assertEquals(Type.NOT_STARTED, decide(Fixtures.snapshot(0.5, prevTime)))
    }

    @Test
    fun `square 0 and last bolus is newer with the requested amount - delivered`() {
        assertEquals(Type.DELIVERED, decide(Fixtures.snapshot(0.5, newTime)))
    }

    @Test
    fun `newer and in progress - delivering`() {
        assertEquals(Type.DELIVERING, decide(Fixtures.snapshot(0.5, newTime, square = 0.5, delivered = 0.2)))
    }

    @Test
    fun `no answer - unknown`() {
        assertEquals(Type.UNKNOWN, decide(null))
    }

    @Test
    fun `no baseline, same amount and nothing in progress - delivered`() {
        assertEquals(Type.DELIVERED, decide(Fixtures.snapshot(0.5, newTime - 30 * min), baselineTime = null, baselineAmount = null))
    }

    @Test
    fun `no baseline, different amount - unknown, never recorded`() {
        assertEquals(Type.UNKNOWN, decide(Fixtures.snapshot(1.2, newTime), baselineTime = null, baselineAmount = null))
        assertEquals(Type.UNKNOWN, decide(Fixtures.snapshot(0.2, newTime), baselineTime = null, baselineAmount = null))
    }

    @Test
    fun `no baseline, in progress - delivering`() {
        assertEquals(Type.DELIVERING, decide(Fixtures.snapshot(0.5, newTime, square = 0.5, delivered = 0.1), baselineTime = null, baselineAmount = null))
    }
}
