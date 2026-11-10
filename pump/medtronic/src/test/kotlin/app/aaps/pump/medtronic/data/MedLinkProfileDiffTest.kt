package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkProfileDiffTest {

    private val pump = DoubleArray(24) { 1.0 }

    @Test
    fun `same profile - no differences`() {
        assertTrue(MedLinkProfileDiff.differences(pump, List(24) { 1.0 }).isEmpty())
    }

    @Test
    fun `hours in a row with the same rates are one range`() {
        val aaps = List(24) { if (it in 8..9) 1.35 else 1.0 }
        assertEquals(listOf(MedLinkProfileDiff.Range(8, 10, 1.0, 1.35)), MedLinkProfileDiff.differences(pump, aaps))
    }

    @Test
    fun `different rates in a row are separate ranges`() {
        val aaps = List(24) { when (it) { 8 -> 1.3; 9 -> 1.35; 23 -> 0.9; else -> 1.0 } }
        assertEquals(
            listOf(
                MedLinkProfileDiff.Range(8, 9, 1.0, 1.3),
                MedLinkProfileDiff.Range(9, 10, 1.0, 1.35),
                MedLinkProfileDiff.Range(23, 24, 1.0, 0.9)
            ),
            MedLinkProfileDiff.differences(pump, aaps)
        )
    }

    @Test
    fun `hour labels`() {
        assertEquals("08:00", MedLinkProfileDiff.hour(8))
        assertEquals("00:00", MedLinkProfileDiff.hour(24))
    }
}
