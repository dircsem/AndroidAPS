package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.Bolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.SMBBolus
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.TBRBolus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManualBolusRuleTest {

    @Test
    fun `a bolus is refused only when one of the same type is pending (4i)`() {
        assertTrue(ManualBolusRule.refuses(setOf(TBRBolus), TBRBolus))
        assertTrue(ManualBolusRule.refuses(setOf(TBRBolus, SMBBolus), SMBBolus))
        assertTrue(ManualBolusRule.refuses(setOf(Bolus), Bolus))
    }

    @Test
    fun `a bolus of another type is not refused (4i)`() {
        assertFalse(ManualBolusRule.refuses(emptySet(), SMBBolus))
        assertFalse(ManualBolusRule.refuses(setOf(TBRBolus), SMBBolus))
        assertFalse(ManualBolusRule.refuses(setOf(TBRBolus), Bolus))
        assertFalse(ManualBolusRule.refuses(setOf(SMBBolus), Bolus))
        assertFalse(ManualBolusRule.refuses(setOf(SMBBolus, Bolus), TBRBolus))
    }

    @Test
    fun `edited amount may only be lowered and is rounded down to the pump step (T16)`() {
        assertEquals(1.5, ManualBolusRule.editedAmount(2.0, 1.5, 0.1)!!, 1e-9)
        assertEquals(1.5, ManualBolusRule.editedAmount(2.0, 1.57, 0.1)!!, 1e-9)
        assertEquals(2.0, ManualBolusRule.editedAmount(2.0, 2.0, 0.1)!!, 1e-9)
        assertEquals(0.3, ManualBolusRule.editedAmount(2.0, 0.3, 0.1)!!, 1e-9)
        assertEquals(1.25, ManualBolusRule.editedAmount(2.0, 1.25, 0.05)!!, 1e-9)
    }

    @Test
    fun `edited amount above the requested, zero or unreadable is rejected (T16)`() {
        assertNull(ManualBolusRule.editedAmount(2.0, 2.1, 0.1))
        assertNull(ManualBolusRule.editedAmount(2.0, 0.0, 0.1))
        assertNull(ManualBolusRule.editedAmount(2.0, 0.05, 0.1))
        assertNull(ManualBolusRule.editedAmount(2.0, -1.0, 0.1))
        assertNull(ManualBolusRule.editedAmount(2.0, null, 0.1))
        assertNull(ManualBolusRule.editedAmount(2.0, Double.NaN, 0.1))
    }
}
