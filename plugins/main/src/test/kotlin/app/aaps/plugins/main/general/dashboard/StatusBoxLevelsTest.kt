package app.aaps.plugins.main.general.dashboard

import app.aaps.core.ui.compose.StatusLevel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StatusBoxLevelsTest {

    @Test
    fun `worse level - critical wins, then warning, then normal`() {
        assertThat(worseStatusLevel(StatusLevel.NORMAL, StatusLevel.CRITICAL)).isEqualTo(StatusLevel.CRITICAL)
        assertThat(worseStatusLevel(StatusLevel.CRITICAL, StatusLevel.WARNING)).isEqualTo(StatusLevel.CRITICAL)
        assertThat(worseStatusLevel(StatusLevel.WARNING, StatusLevel.NORMAL)).isEqualTo(StatusLevel.WARNING)
        assertThat(worseStatusLevel(StatusLevel.UNSPECIFIED, StatusLevel.WARNING)).isEqualTo(StatusLevel.WARNING)
    }

    @Test
    fun `worse level - a known level wins over no data`() {
        assertThat(worseStatusLevel(StatusLevel.UNSPECIFIED, StatusLevel.NORMAL)).isEqualTo(StatusLevel.NORMAL)
        assertThat(worseStatusLevel(StatusLevel.NORMAL, StatusLevel.UNSPECIFIED)).isEqualTo(StatusLevel.NORMAL)
        assertThat(worseStatusLevel(StatusLevel.UNSPECIFIED, StatusLevel.UNSPECIFIED)).isEqualTo(StatusLevel.UNSPECIFIED)
        assertThat(worseStatusLevel(null, StatusLevel.WARNING)).isEqualTo(StatusLevel.WARNING)
        assertThat(worseStatusLevel(null, null)).isNull()
    }

    // B5: pump battery box (Glass and circle-top)

    @Test
    fun `battery level only - shows the level, colored by the level`() {
        val box = levelOrAgeBox(age = "-", ageStatus = StatusLevel.UNSPECIFIED, level = "20%", levelStatus = StatusLevel.CRITICAL)
        assertThat(box).isEqualTo(StatusBoxValue("20%", StatusLevel.CRITICAL))
    }

    @Test
    fun `battery level at the warning threshold - yellow`() {
        val box = levelOrAgeBox(age = "-", ageStatus = StatusLevel.UNSPECIFIED, level = "51%", levelStatus = StatusLevel.WARNING)
        assertThat(box.level).isEqualTo(StatusLevel.WARNING)
    }

    @Test
    fun `battery age only (MedLink battery info = age) - shows the age, colored by the age`() {
        val box = levelOrAgeBox(age = "9d 2h", ageStatus = StatusLevel.WARNING, level = null, levelStatus = StatusLevel.UNSPECIFIED)
        assertThat(box).isEqualTo(StatusBoxValue("9d 2h", StatusLevel.WARNING))
    }

    @Test
    fun `battery level and age - shows the level, colored by the worse`() {
        val oldGood = levelOrAgeBox(age = "20d", ageStatus = StatusLevel.CRITICAL, level = "80%", levelStatus = StatusLevel.NORMAL)
        assertThat(oldGood).isEqualTo(StatusBoxValue("80%", StatusLevel.CRITICAL))
        val newLow = levelOrAgeBox(age = "1d", ageStatus = StatusLevel.NORMAL, level = "25%", levelStatus = StatusLevel.CRITICAL)
        assertThat(newLow).isEqualTo(StatusBoxValue("25%", StatusLevel.CRITICAL))
    }

    @Test
    fun `battery with no data - shows the age placeholder, no color`() {
        val box = levelOrAgeBox(age = "-", ageStatus = StatusLevel.UNSPECIFIED, level = null, levelStatus = StatusLevel.UNSPECIFIED)
        assertThat(box).isEqualTo(StatusBoxValue("-", StatusLevel.UNSPECIFIED))
    }

    @Test
    fun `battery level good and no change event - normal, not uncolored`() {
        val box = levelOrAgeBox(age = "-", ageStatus = StatusLevel.UNSPECIFIED, level = "90%", levelStatus = StatusLevel.NORMAL)
        assertThat(box.level).isEqualTo(StatusLevel.NORMAL)
    }
}
