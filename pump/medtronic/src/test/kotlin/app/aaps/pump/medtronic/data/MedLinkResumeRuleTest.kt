package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkResumeRule.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * B12 (field night 2026-11-16): the app crashed at 02:31:50 while the TBR plan had stopped the pump. The plan lived only
 * in memory, so after the restart nothing started the pump again (02:32 -> 06:00). The status read saw the pump
 * suspended 61 times. This rule decides what the status read does then.
 */
class MedLinkResumeRuleTest {

    private val min = 60_000L
    private val now = 1_000_000_000L

    private fun decide(
        suspended: Boolean = true,
        autoStopAt: Long = now - 6 * min,
        planHasSteps: Boolean = false,
        nextReactivateAt: Long = 0L,
        planClearedAt: Long = 0L,
        zeroTbrLimitActive: Boolean = false,
    ) = MedLinkResumeRule.decide(now, suspended, autoStopAt, planHasSteps, nextReactivateAt, planClearedAt, zeroTbrLimitActive)

    @Test
    fun `field case - suspended by the plan, plan lost in a restart - start`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min))
    }

    @Test
    fun `pump running - nothing`() {
        assertEquals(Action.NONE, decide(suspended = false))
    }

    @Test
    fun `last stop by the user or on the pump - never started by the app, no alarm`() {
        assertEquals(Action.NONE, decide(autoStopAt = 0L))
        assertEquals(Action.NONE, decide(autoStopAt = 0L, planClearedAt = now - 60 * min))
    }

    @Test
    fun `T21 waits for the user answer - nothing`() {
        assertEquals(Action.NONE, decide(zeroTbrLimitActive = true, autoStopAt = now - 60 * min))
    }

    @Test
    fun `automatic stop just now - wait, a new plan can be on its way`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 4 * min))
    }

    @Test
    fun `plan cleared just now - wait, the new plan is being built`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 60 * min, planClearedAt = now - 1 * min))
    }

    @Test
    fun `plan with a REACTIVATE still to come - the plan starts the pump`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 20 * min, planHasSteps = true, nextReactivateAt = now + 10 * min))
    }

    @Test
    fun `plan REACTIVATE late by less than the margin - wait`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 20 * min, planHasSteps = true, nextReactivateAt = now - 9 * min))
    }

    @Test
    fun `plan REACTIVATE late by more than the margin - start and alarm`() {
        assertEquals(Action.ALARM, decide(autoStopAt = now - 20 * min, planHasSteps = true, nextReactivateAt = now - 11 * min))
    }

    @Test
    fun `plan with steps but no REACTIVATE - same as no plan`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, planHasSteps = true, nextReactivateAt = 0L))
    }

    @Test
    fun `no plan and still suspended long after the stop - the start did not work - alarm`() {
        assertEquals(Action.ALARM, decide(autoStopAt = now - 15 * min))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 4 * 60 * min))
    }
}
