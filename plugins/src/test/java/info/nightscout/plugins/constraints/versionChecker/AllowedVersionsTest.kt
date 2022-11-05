package info.nightscout.plugins.constraints.versionChecker

import info.nightscout.androidaps.plugins.constraints.versionChecker.AllowedVersions
import org.joda.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedVersionsTest {

    private fun generateSupportedVersions(): String =
        JSONArray()
            // Android API versions
            .put(JSONObject().apply {
                put("minAndroid", 1) // 1.0
                put("maxAndroid", 23) // 6.0.1
            })
            .put(JSONObject().apply {
                put("minAndroid", 24) // 7.0
                put("maxAndroid", 25) // 7.1.2
                put("supported", "2.6.2")
            })
            .put(JSONObject().apply {
                put("minAndroid", 26) // 8.0
                put("maxAndroid", 27) // 8.1
                put("supported", "2.8.2")
            })
            .put(JSONObject().apply {
                put("minAndroid", 28) // 9.0
                put("maxAndroid", 99)
                put("supported", "2.8.2")
            })
            // Version time limitation
            .put(JSONObject().apply {
                put("endDate", "2021-11-07")
                put("version", "2.9.0-beta1")
            })
            .put(JSONObject().apply {
                put("endDate", "2021-11-07")
                put("version", "3.0-beta1")
            })
            .toString()

    @Test
    fun generateSupportedVersionsTest() {
        val definition = generateSupportedVersions()
        assertNull(AllowedVersions().findByApi(definition, 0))
        assertFalse(AllowedVersions().findByApi(definition, 1)?.has("supported") ?: true)
        assertFalse(AllowedVersions().findByApi(definition, 23)?.has("supported") ?: true)
        assertEquals("2.6.2", AllowedVersions().findByApi(definition, 24)?.getString("supported"))
        assertEquals("2.6.2", AllowedVersions().findByApi(definition, 25)?.getString("supported"))
        assertEquals("2.8.2", AllowedVersions().findByApi(definition, 26)?.getString("supported"))
        assertEquals("2.8.2", AllowedVersions().findByApi(definition, 27)?.getString("supported"))
        assertEquals("2.8.2", AllowedVersions().findByApi(definition, 28)?.getString("supported"))
    }

    @Test
    fun findByVersionTest() {
        //val definition = AllowedVersions().generateSupportedVersions()
        val definition =
            "[{\"minAndroid\":1,\"maxAndroid\":23},{\"minAndroid\":24,\"maxAndroid\":25,\"supported\":\"2.6.2\"},{\"minAndroid\":26,\"maxAndroid\":27,\"supported\":\"2.8.2\"},{\"minAndroid\":28,\"maxAndroid\":99,\"supported\":\"2.8.2\"},{\"endDate\":\"2021-11-07\",\"version\":\"2.9.0-beta1\"},{\"endDate\":\"2021-11-02\",\"version\":\"3.0-beta1\"},{\"endDate\":\"2021-11-04\",\"version\":\"3.0-beta2\"},{\"endDate\":\"2021-11-10\",\"version\":\"3.0-beta3\"},{\"endDate\":\"2021-11-14\",\"version\":\"3.0-beta4\"}\n" +
                " ,{\"endDate\":\"2021-11-16\",\"version\":\"3.0-beta5\"}\n" +
                "]"
        assertNull(AllowedVersions().findByVersion(definition, "2.6.0"))
        assertTrue(AllowedVersions().findByVersion(definition, "2.9.0-beta1")?.has("endDate") ?: false)
        assertEquals("2021-11-07", AllowedVersions().findByVersion(definition, "2.9.0-beta1")?.getString("endDate"))
    }

    @Test
    fun endDateToMilliseconds() {
        val definition = generateSupportedVersions()
        val endDate = AllowedVersions().endDateToMilliseconds(AllowedVersions().findByVersion(definition, "2.9.0-beta1")?.getString("endDate") ?: "1000/01/01")
        val dateTime = LocalDate(endDate)
        assertEquals(2021, dateTime.year)
        assertEquals(11, dateTime.monthOfYear)
        assertEquals(7, dateTime.dayOfMonth)

        assertNull(AllowedVersions().endDateToMilliseconds("abdef"))
    }
}