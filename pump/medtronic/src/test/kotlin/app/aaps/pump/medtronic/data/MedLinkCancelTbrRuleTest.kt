package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Field 2026-11-19 16:41-16:51: after an app restart the plan (in memory) was lost; the user changed the loop mode and
 * AAPS asked to cancel the TBR while the pump was suspended. `cancelTempBasal` sent nothing and never answered the
 * queue: the queue waited 10 min (no TBR, no bolus). User rule (2026-11-19): START only when the loop does not run;
 * when it runs it decides itself. The queue always gets its answer (START answer, or at once).
 */
class MedLinkCancelTbrRuleTest {

    private fun start(
        hasPlan: Boolean = false,
        firstIsUnsentReactivate: Boolean = false,
        firstIsSentSuspend: Boolean = false,
        pumpSuspended: Boolean = true,
        loopEnactsTbr: Boolean = false,
    ) = MedLinkCancelTbrRule.shouldStart(hasPlan, firstIsUnsentReactivate, firstIsSentSuspend, pumpSuspended, loopEnactsTbr)

    @Test
    fun `field case - no plan, pump suspended, loop suspended by the user - START`() {
        assertTrue(start(loopEnactsTbr = false))
    }

    @Test
    fun `no plan, pump suspended, closed loop or LGS runs - no START, the loop decides`() {
        assertFalse(start(loopEnactsTbr = true))
    }

    @Test
    fun `no plan, pump running - nothing to start`() {
        assertFalse(start(pumpSuspended = false))
        assertFalse(start(pumpSuspended = false, loopEnactsTbr = true))
    }

    @Test
    fun `with a plan - as before - START when the pump is or will be suspended by the plan`() {
        assertTrue(start(hasPlan = true, firstIsUnsentReactivate = true, pumpSuspended = false, loopEnactsTbr = true))
        assertTrue(start(hasPlan = true, firstIsSentSuspend = true, pumpSuspended = false, loopEnactsTbr = true))
        assertTrue(start(hasPlan = true, pumpSuspended = true, loopEnactsTbr = true))
    }

    @Test
    fun `with a plan that starts with a bolus and a running pump - no START (and the queue is answered at once)`() {
        assertFalse(start(hasPlan = true, pumpSuspended = false, loopEnactsTbr = true))
    }
}
