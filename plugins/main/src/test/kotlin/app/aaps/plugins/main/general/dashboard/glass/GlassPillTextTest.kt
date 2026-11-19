package app.aaps.plugins.main.general.dashboard.glass

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** T28: battery pill `3d3h·38`, each value colored at its real place. */
class GlassPillTextTest {

    private val template = "$GLASS_MARK_FIRST·$GLASS_MARK_SECOND"

    @Test
    fun `no spaces and the right places`() {
        val r = placeTwoValues(template, "3d3h", "38")
        assertThat(r.text).isEqualTo("3d3h·38")
        assertThat(r.text.substring(r.first!!)).isEqualTo("3d3h")
        assertThat(r.text.substring(r.second!!)).isEqualTo("38")
    }

    @Test
    fun `the same value on both sides - two different places`() {
        val r = placeTwoValues(template, "38", "38")
        assertThat(r.first).isEqualTo(0..1)
        assertThat(r.second).isEqualTo(3..4)
    }

    @Test
    fun `one value inside the other - not found in the wrong place`() {
        val r = placeTwoValues(template, "38%", "38")
        assertThat(r.text).isEqualTo("38%·38")
        assertThat(r.text.substring(r.second!!)).isEqualTo("38")
        assertThat(r.second!!.first).isEqualTo(4)
    }

    @Test
    fun `empty MedLink value - no range for it`() {
        val r = placeTwoValues(template, "3d3h", "")
        assertThat(r.text).isEqualTo("3d3h·")
        assertThat(r.second).isNull()
    }

    @Test
    fun `MedLink dash value - colored as a value`() {
        val r = placeTwoValues(template, "3d3h", "-")
        assertThat(r.text.substring(r.second!!)).isEqualTo("-")
    }

    @Test
    fun `a translation can put the values the other way round`() {
        val r = placeTwoValues("$GLASS_MARK_SECOND | $GLASS_MARK_FIRST", "3d3h", "38")
        assertThat(r.text).isEqualTo("38 | 3d3h")
        assertThat(r.text.substring(r.first!!)).isEqualTo("3d3h")
        assertThat(r.text.substring(r.second!!)).isEqualTo("38")
    }
}

/** T27: time since the last bolus in the Last bolus pill. */
class GlassAgeSinceTest {

    private val now = 1_000_000_000_000L
    private val min = 60_000L

    @Test fun `no bolus - no time`() { assertThat(glassAgeSince(null, now)).isNull() }
    @Test fun `bolus in the future - ignored`() { assertThat(glassAgeSince(now + min, now)).isNull() }
    @Test fun `just now - 0m`() { assertThat(glassAgeSince(now, now)).isEqualTo(GlassAge(0, GlassAge.AgeUnit.MINUTES)) }
    @Test fun `59 min - 59m`() { assertThat(glassAgeSince(now - 59 * min - 30_000L, now)).isEqualTo(GlassAge(59, GlassAge.AgeUnit.MINUTES)) }
    @Test fun `1 h - 1h`() { assertThat(glassAgeSince(now - 60 * min, now)).isEqualTo(GlassAge(1, GlassAge.AgeUnit.HOURS)) }
    @Test fun `23 h 59 min - 23h`() { assertThat(glassAgeSince(now - (24 * 60 - 1) * min, now)).isEqualTo(GlassAge(23, GlassAge.AgeUnit.HOURS)) }
    @Test fun `over 24 h - days`() { assertThat(glassAgeSince(now - 50 * 60 * min, now)).isEqualTo(GlassAge(2, GlassAge.AgeUnit.DAYS)) }
}
