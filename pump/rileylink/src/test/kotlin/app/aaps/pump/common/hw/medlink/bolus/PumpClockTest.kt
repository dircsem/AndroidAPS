package app.aaps.pump.common.hw.medlink.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale

class PumpClockTest {

    private fun t(text: String) = SimpleDateFormat("dd-MM-yy HH:mm", Locale.US).parse(text)!!.time

    @Test
    fun `a pump time near the read keeps its year`() {
        assertEquals(t("09-11-26 11:29"), PumpClock.normalizeYear(t("09-11-26 11:29"), t("09-11-26 11:30")))
    }

    @Test
    fun `year 00 (seen in field logs) is moved to the year of the read`() {
        assertEquals(t("09-11-26 11:03"), PumpClock.normalizeYear(t("09-11-00 11:03"), t("09-11-26 11:30")))
    }

    @Test
    fun `a bolus from late december read in january goes to the previous year`() {
        assertEquals(t("31-12-25 23:50"), PumpClock.normalizeYear(t("31-12-00 23:50"), t("01-01-26 00:10")))
    }
}
