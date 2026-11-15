package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.MedtronicTestBase
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.BasalProfileEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ---- B6 (user, 2026-11-14): ISF and IC differences must be found, not only the basal ----

    private val roundBasal = { rate: Double -> rate }

    @Test
    fun `pump profile with another ISF and carb ratio - both differences found, nothing else`() {
        val pump = parse(answer)!!
        val aaps = parse(answer.map {
            when (it) {
                "rate 1: 12gr/u from 00:00"   -> "rate 1: 11gr/u from 00:00"
                "rate 1:  39mg/dl from 00:00" -> "rate 1:  45mg/dl from 00:00"
                else                          -> it
            }
        })!!
        val kinds = MedLinkPumpProfileDiff.differences(pump, aaps, roundBasal).map { it.kind }.toSet()
        assertEquals(setOf(MedLinkPumpProfileDiff.Kind.ISF, MedLinkPumpProfileDiff.Kind.IC), kinds)
    }

    @Test
    fun `the same profile - no difference`() {
        assertTrue(MedLinkPumpProfileDiff.differences(parse(answer)!!, parse(answer)!!, roundBasal).isEmpty())
    }

    @Test
    fun `ISF with one space or three digits is read (the read must not be lost)`() {
        assertEquals(39.0, parse(answer.map { if (it == "rate 1:  39mg/dl from 00:00") "rate 1: 39mg/dl from 00:00" else it })!!.getIsfMgdlTimeFromMidnight(0), 0.001)
        assertEquals(100.0, parse(answer.map { if (it == "rate 1:  39mg/dl from 00:00") "rate 1: 100mg/dl from 00:00" else it })!!.getIsfMgdlTimeFromMidnight(0), 0.001)
    }

    @Test
    fun `ISF in mmol per l with decimals is read`() {
        // 2.2 mmol/l = 39.6 mg/dl
        val profile = parse(answer.map { if (it == "rate 1:  39mg/dl from 00:00") "rate 1: 2.2mmol/l from 00:00" else it })
        assertEquals(39.6, profile!!.getIsfMgdlTimeFromMidnight(0), 0.5)
    }

    @Test
    fun `targets with the non-breaking hyphen of the MedLink answer are read`() {
        val profile = parse(answer.map { if (it == "rate 1:100-100 from 00:00") "rate 1: 70\u2011140 from 00:00" else it })
        assertEquals(70.0, profile!!.getTargetLowMgdlTimeFromMidnight(0), 0.001)
        assertEquals(140.0, profile.getTargetHighMgdlTimeFromMidnight(0), 0.001)
    }

    @Test
    fun `mmol per l profile - ISF and targets read as mmol, not as mg per dl`() {
        val profile = parse(answer.map {
            when {
                it == "rate 1:  39mg/dl from 00:00" -> "rate 1: 2.2mmol/l from 00:00"
                it.startsWith("rate 1:100-100")     -> "rate 1: 5.5-5.5 from 00:00"
                it.startsWith("rate 2: 90-100")     -> "rate 2: 5.0-5.5 from 01:00"
                it.startsWith("rate 3: 80- 90")     -> "rate 3: 4.4-5.0 from 13:00"
                it.startsWith("rate 4: 80- 90")     -> "rate 4: 4.4-5.0 from 22:00"
                else                                -> it
            }
        })!!
        assertEquals(99.0, profile.getTargetLowMgdlTimeFromMidnight(0), 1.0)
        assertEquals(79.3, profile.getTargetLowMgdlTimeFromMidnight(14 * 3600), 1.0)
    }

    @Test
    fun `ISF at 12_30 (field log 2026-11-15) - no AAPS profile, but the parsed JSON is kept for the comparison`() {
        val parser = MedLinkProfileParserImpl<Any>(activePlugin, aapsLogger, dateUtil)
        val lines = answer.map { if (it == "rate 1:  39mg/dl from 00:00") "rate 1:  35mg/dl from 00:00\nrate 2:  30mg/dl from 12:30" else it }
            .flatMap { it.split("\n") }
        val profile = parser.parseProfile(Supplier<Stream<String>> { lines.stream() }, basal())
        assertEquals(null, profile)
        assertNotNull(parser.lastProfileJson)
        assertEquals(listOf("12:30"), MedLinkPumpProfileDiff.offHourTimes(parser.lastProfileJson!!))
    }

    @Test
    fun `carb ratio with decimals is kept`() {
        val profile = parse(answer.map { if (it == "rate 2: 10gr/u from 17:00") "rate 2: 10.5gr/u from 17:00" else it })
        assertEquals(10.5, profile!!.getIcTimeFromMidnight(18 * 3600), 0.001)
    }
}
