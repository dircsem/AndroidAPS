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
