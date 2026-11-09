package app.aaps.pump.common.hw.medlink.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PumpLockRuleTest {

    @Test
    fun `one confirmed command without effect is not a lock`() {
        assertNull(PumpLockRule.decide(0))
        assertNull(PumpLockRule.decide(1))
    }

    @Test
    fun `two or more confirmed commands without effect lock the pump`() {
        assertEquals(PumpBlock.PUMP_LOCKED, PumpLockRule.decide(PumpLockRule.LOCK_AFTER))
        assertEquals(PumpBlock.PUMP_LOCKED, PumpLockRule.decide(5))
    }
}
