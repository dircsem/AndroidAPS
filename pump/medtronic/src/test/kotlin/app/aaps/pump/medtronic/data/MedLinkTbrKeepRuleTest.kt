package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkTbrKeepRule.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MedLinkTbrKeepRuleTest {

    private val min = 60_000L
    private val start = 1_800_000_000_000L

    /** A 200 % plan made at [start] for 30 min; a new 30 min TBR [after] minutes later. */
    private fun decide(after: Int, newPercent: Int = 200, planPercent: Int = 200, hasPlan: Boolean = true, plannedAt: Long = start): Action {
        val now = start + after * min
        return MedLinkTbrKeepRule.decide(now, hasPlan, planPercent, newPercent, plannedAt, start + 30 * min, now + 30 * min).action
    }

    @Test
    fun `same percent 5 and 10 min later - the plan is kept as it is`() {
        assertEquals(Action.KEEP, decide(after = 5))
        assertEquals(Action.KEEP, decide(after = 10))
        assertEquals(Action.KEEP, decide(after = 14))
    }

    @Test
    fun `same percent 15 min or more later - only the new period is added`() {
        assertEquals(Action.EXTEND, decide(after = 15))
        assertEquals(Action.EXTEND, decide(after = 25))
    }

    @Test
    fun `after an extension the 15 min count again from it`() {
        // extended at 15 min: 5 min later the plan is kept as it is
        assertEquals(Action.KEEP, decide(after = 20, plannedAt = start + 15 * min))
        assertEquals(Action.EXTEND, decide(after = 30 - 1, plannedAt = start + 14 * min))
    }

    @Test
    fun `a different percent - a new plan`() {
        assertEquals(Action.REBUILD, decide(after = 5, newPercent = 180))
        assertEquals(Action.REBUILD, decide(after = 20, newPercent = 220))
    }

    @Test
    fun `the same for stop and start plans below 100 percent`() {
        assertEquals(Action.KEEP, decide(after = 5, newPercent = 40, planPercent = 40))
        assertEquals(Action.EXTEND, decide(after = 15, newPercent = 40, planPercent = 40))
        assertEquals(Action.REBUILD, decide(after = 5, newPercent = 30, planPercent = 40))
    }

    @Test
    fun `0 and 100 percent always go their own way`() {
        assertEquals(Action.REBUILD, decide(after = 5, newPercent = 0, planPercent = 0))
        assertEquals(Action.REBUILD, decide(after = 5, newPercent = 100, planPercent = 100))
    }

    @Test
    fun `no plan or a plan that ended - a new plan`() {
        assertEquals(Action.REBUILD, decide(after = 5, hasPlan = false))
        assertEquals(Action.REBUILD, decide(after = 31))
    }

    @Test
    fun `percent of an absolute TBR`() {
        assertEquals(200, MedLinkTbrKeepRule.percentOf(2.4, 1.2))
        assertEquals(33, MedLinkTbrKeepRule.percentOf(0.4, 1.2))
        assertEquals(0, MedLinkTbrKeepRule.percentOf(0.4, 0.0))
    }
}
