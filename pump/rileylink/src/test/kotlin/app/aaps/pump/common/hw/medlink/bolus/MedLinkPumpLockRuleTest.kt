package app.aaps.pump.common.hw.medlink.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MedLinkPumpLockRuleTest {

    @Test
    fun `one confirmed command without effect is not a lock`() {
        assertNull(MedLinkPumpLockRule.decide(0))
        assertNull(MedLinkPumpLockRule.decide(1))
    }

    @Test
    fun `two or more confirmed commands without effect lock the pump`() {
        assertEquals(MedLinkPumpBlock.PUMP_LOCKED, MedLinkPumpLockRule.decide(MedLinkPumpLockRule.LOCK_AFTER))
        assertEquals(MedLinkPumpBlock.PUMP_LOCKED, MedLinkPumpLockRule.decide(5))
    }

    @Test
    fun `the configured command attempts decide when the pump is reported (4aa)`() {
        assertNull(MedLinkPumpLockRule.decide(4, lockAfter = 5))
        assertEquals(MedLinkPumpBlock.PUMP_LOCKED, MedLinkPumpLockRule.decide(5, lockAfter = 5))
        assertEquals(MedLinkPumpBlock.PUMP_LOCKED, MedLinkPumpLockRule.decide(1, lockAfter = 1))
        assertNull(MedLinkPumpLockRule.decide(0, lockAfter = 0), "a setting below 1 counts as 1")
    }
}
