package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** T31: the setting value read by the basal pill and the basal graph. */
class MedLinkTbrDisplayMinutesTest {

    @Test
    fun `allowed values stay`() {
        for (v in listOf(5, 10, 15, 30, 60, 120)) assertEquals(v, MedLinkTbrDisplayMinutes.snap(v))
    }

    @Test
    fun `an old free value is read as the nearest allowed value below it`() {
        assertEquals(15, MedLinkTbrDisplayMinutes.snap(20))
        assertEquals(30, MedLinkTbrDisplayMinutes.snap(45))
        assertEquals(60, MedLinkTbrDisplayMinutes.snap(90))
        assertEquals(5, MedLinkTbrDisplayMinutes.snap(4))
        assertEquals(120, MedLinkTbrDisplayMinutes.snap(200))
    }
}
