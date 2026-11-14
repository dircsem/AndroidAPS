package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.MedtronicTestBase
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.BasalProfileEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.function.Supplier
import java.util.stream.Stream

class MedLinkProfileParserImplTest : MedtronicTestBase() {

    /** Real `F` answer (logs/basal_problem.log). */
    private val answer = listOf(
        "f command confirmed",
        "bolus wizard settings:",
        "max. bolus: 12.0u",
        "easy bolus step: 0.1u",
        "carb ratios:",
        "rate 1: 12gr/u from 00:00",
        "rate 2: 10gr/u from 17:00",
        "insulin sensitivities:",
        "rate 1:  39mg/dl from 00:00",
        "bg targets:",
        "rate 1:100-100 from 00:00",
        "rate 2: 90-100 from 01:00",
        "rate 3: 80- 90 from 13:00",
        "rate 4: 80- 90 from 22:00",
        "ready"
    )

    private fun basal(): BasalProfile = BasalProfile(aapsLogger).apply {
        // the pump answer can give the same time twice (seen in the log): must not break the profile
        addEntry(BasalProfileEntry(0.75, 0, 0))
        addEntry(BasalProfileEntry(0.75, 0, 0))
        addEntry(BasalProfileEntry(1.0, 4, 0))
        addEntry(BasalProfileEntry(1.0, 4, 0))
        generateRawDataFromEntries()
    }

    private fun parse(lines: List<String>) =
        MedLinkProfileParserImpl<Any>(activePlugin, aapsLogger, dateUtil).parseProfile(Supplier<Stream<String>> { lines.stream() }, basal())

    @Test
    fun `real answer gives carb ratios, ISF, targets and basal`() {
        val profile = parse(answer)
        assertNotNull(profile)
        profile!!
        assertEquals(12.0, profile.getIcTimeFromMidnight(0), 0.001)
        assertEquals(10.0, profile.getIcTimeFromMidnight(17 * 3600), 0.001)
        assertEquals(39.0, profile.getIsfMgdlTimeFromMidnight(0), 0.001)
        assertEquals(100.0, profile.getTargetLowMgdlTimeFromMidnight(0), 0.001)
        assertEquals(90.0, profile.getTargetLowMgdlTimeFromMidnight(2 * 3600), 0.001)
        assertEquals(80.0, profile.getTargetLowMgdlTimeFromMidnight(14 * 3600), 0.001)
        assertEquals(90.0, profile.getTargetHighMgdlTimeFromMidnight(14 * 3600), 0.001)
        assertEquals(0.75, profile.getBasalTimeFromMidnight(3600), 0.001)
        assertEquals(1.0, profile.getBasalTimeFromMidnight(5 * 3600), 0.001)
    }

    @Test
    fun `carb ratio with decimals is kept`() {
        val profile = parse(answer.map { if (it == "rate 2: 10gr/u from 17:00") "rate 2: 10.5gr/u from 17:00" else it })
        assertEquals(10.5, profile!!.getIcTimeFromMidnight(18 * 3600), 0.001)
    }
}
