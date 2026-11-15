package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkPumpProfileDiff.Kind
import app.aaps.pump.medtronic.data.MedLinkPumpProfileDiff.Range
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    // ---- B6 field log 2026-11-15: the pump profile has ISF times at 12:30 and 19:30 (AAPS profiles: whole hours only) ----

    /** The JSON the parser built from the real answer (logs/profile_read.log), basal shortened to the hours used. */
    private val pumpJson = JSONObject(
        """{"dia":"5.0","carbratio":[{"time":"00:00","value":12},{"time":"17:00","value":10}],""" +
            """"sens":[{"time":"00:00","value":35},{"time":"03:00","value":45},{"time":"09:00","value":30},{"time":"10:00","value":45},""" +
            """{"time":"12:30","value":30},{"time":"16:00","value":50},{"time":"19:30","value":35},{"time":"22:00","value":50}],""" +
            """"target_high":[{"time":"00:00","value":100},{"time":"01:00","value":100},{"time":"13:00","value":90},{"time":"22:00","value":90}],""" +
            """"target_low":[{"time":"00:00","value":100},{"time":"01:00","value":90},{"time":"13:00","value":80},{"time":"22:00","value":80}],""" +
            """"units":"mg/dl","basal":[{"time":"00:00","value":0.75},{"time":"07:00","value":1.2000000000000002},{"time":"08:00","value":1.3}]}"""
    )
    private val roundBasal = { rate: Double -> Math.round(rate * 40) / 40.0 }

    @Test
    fun `values from the pump answer - half hour times kept exactly`() {
        val values = MedLinkPumpProfileDiff.values(pumpJson, roundBasal)
        assertNotNull(values)
        val isf = values!!.getValue(Kind.ISF)
        assertEquals(45.0, isf[24], 0.001) // 12:00
        assertEquals(30.0, isf[25], 0.001) // 12:30
        assertEquals(50.0, isf[38], 0.001) // 19:00
        assertEquals(35.0, isf[39], 0.001) // 19:30
        assertEquals(1.2, values.getValue(Kind.BASAL)[14], 0.0001) // 07:00, rounded to the pump step
        assertEquals(12.0, values.getValue(Kind.IC)[0], 0.001)
        assertEquals(90.0, values.getValue(Kind.TARGET_LOW)[2], 0.001) // 01:00
    }

    @Test
    fun `times not on the hour are listed`() {
        assertEquals(listOf("12:30", "19:30"), MedLinkPumpProfileDiff.offHourTimes(pumpJson))
    }

    @Test
    fun `a different ISF at 12_30 is found against whole hour AAPS values`() {
        val pump = MedLinkPumpProfileDiff.values(pumpJson, roundBasal)!!
        // AAPS: the same, but ISF 45 the whole 12:00 hour
        val aaps = pump.toMutableMap().apply { put(Kind.ISF, pump.getValue(Kind.ISF).mapIndexed { i, v -> if (i == 25) 45.0 else v }) }
        assertEquals(listOf(Range(Kind.ISF, 25, 26, 30.0, 45.0)), MedLinkPumpProfileDiff.differences(pump, aaps))
    }

    @Test
    fun `mmol per l pump values are compared in mg per dl`() {
        val json = JSONObject(pumpJson.toString()).put("units", "mmol").put("sens", JSONArray("""[{"time":"00:00","value":2.0}]"""))
        val isf = MedLinkPumpProfileDiff.values(json, roundBasal)!!.getValue(Kind.ISF)
        assertEquals(36.0, isf[0], 0.1)
    }

    @Test
    fun `an answer without a list gives no values`() {
        assertEquals(null, MedLinkPumpProfileDiff.values(JSONObject("""{"units":"mg/dl"}"""), roundBasal))
    }
}
