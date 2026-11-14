package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkPumpProfileDiff.Kind
import app.aaps.pump.medtronic.data.MedLinkPumpProfileDiff.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkPumpProfileDiffTest {

    private val slots = MedLinkPumpProfileDiff.SLOTS

    @Test
    fun `same values - no differences`() {
        assertTrue(MedLinkPumpProfileDiff.differences(Kind.IC, List(slots) { 10.0 }, List(slots) { 10.0 }).isEmpty())
    }

    @Test
    fun `slots in a row with the same pair are one range`() {
        // carb ratio 10 on the pump, 9 in AAPS from 17:00 to midnight
        val pump = List(slots) { if (it >= 34) 10.0 else 12.0 }
        val aaps = List(slots) { if (it >= 34) 9.0 else 12.0 }
        assertEquals(listOf(Range(Kind.IC, 34, 48, 10.0, 9.0)), MedLinkPumpProfileDiff.differences(Kind.IC, pump, aaps))
    }

    @Test
    fun `half hour differences are kept`() {
        val pump = List(slots) { if (it == 9) 1.0 else 0.95 }
        val aaps = List(slots) { 0.95 }
        assertEquals(listOf(Range(Kind.BASAL, 9, 10, 1.0, 0.95)), MedLinkPumpProfileDiff.differences(Kind.BASAL, pump, aaps))
    }

    @Test
    fun `differences inside the tolerance are ignored`() {
        // ISF 39.8 vs 40 mg/dl: rounding only
        assertTrue(MedLinkPumpProfileDiff.differences(Kind.ISF, List(slots) { 39.8 }, List(slots) { 40.0 }).isEmpty())
        // basal 0.0004 U/h: rounding only
        assertTrue(MedLinkPumpProfileDiff.differences(Kind.BASAL, List(slots) { 0.7504 }, List(slots) { 0.75 }).isEmpty())
    }

    @Test
    fun `time labels`() {
        assertEquals("00:00", MedLinkPumpProfileDiff.time(0))
        assertEquals("04:30", MedLinkPumpProfileDiff.time(9))
        assertEquals("24:00", MedLinkPumpProfileDiff.time(48))
    }
}
