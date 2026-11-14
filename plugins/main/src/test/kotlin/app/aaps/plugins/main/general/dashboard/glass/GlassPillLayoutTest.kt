package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.ACTIVITY
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.BATTERY
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.CANNULA
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.DELIVERED
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.IOB
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.LAST_BOLUS
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.LAST_CARBS
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.LOOP
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.BATTERY_PAIR
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.RESERVOIR
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.SENSOR
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.TARGET
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.TBR
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.TOOLS
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlassPillLayoutTest {

    // ---- which pills are shown ----

    @Test
    fun `user selection with MedLink - sketch A order`() {
        val shown = shownGlassSlots(
            selected = listOf(GlassPillId.CANNULA, GlassPillId.BATTERY, GlassPillId.BASAL_RATE, GlassPillId.SENSOR, GlassPillId.TARGET, GlassPillId.LAST_BOLUS),
            hasMedLinkBattery = true, hasDelivered = true
        )
        assertThat(shown).containsExactly(CANNULA, BATTERY_PAIR, TBR, DELIVERED, SENSOR, TARGET, LAST_BOLUS, TOOLS).inOrder()
    }

    @Test
    fun `batteries in one pill only with a MedLink battery, tools always last`() {
        assertThat(shownGlassSlots(listOf(GlassPillId.BATTERY), hasMedLinkBattery = false, hasDelivered = false)).containsExactly(BATTERY, TOOLS).inOrder()
        assertThat(shownGlassSlots(listOf(GlassPillId.BATTERY), hasMedLinkBattery = true, hasDelivered = false)).containsExactly(BATTERY_PAIR, TOOLS).inOrder()
        assertThat(shownGlassSlots(listOf(GlassPillId.TARGET), hasMedLinkBattery = false, hasDelivered = false)).containsExactly(TARGET, TOOLS).inOrder()
    }

    @Test
    fun `MedLink only pills need their parent pill and their data`() {
        assertThat(shownGlassSlots(listOf(GlassPillId.BASAL_RATE), hasMedLinkBattery = true, hasDelivered = true)).containsExactly(TBR, DELIVERED, TOOLS).inOrder()
        assertThat(shownGlassSlots(emptyList(), hasMedLinkBattery = true, hasDelivered = true)).containsExactly(TOOLS)
    }

    @Test
    fun `everything selected - the fixed order`() {
        val shown = shownGlassSlots(GlassPillId.entries, hasMedLinkBattery = true, hasDelivered = true)
        assertThat(shown).containsExactly(
            RESERVOIR, CANNULA, BATTERY_PAIR, TBR, DELIVERED, SENSOR, LOOP, ACTIVITY, TARGET, LAST_BOLUS, IOB, LAST_CARBS, TOOLS
        ).inOrder()
    }

    // ---- where they go ----

    @Test
    fun `sketch A with the battery pill - 3 and 3 beside the ring, one row of 2`() {
        val layout = layoutGlassPills(listOf(CANNULA, BATTERY_PAIR, TBR, DELIVERED, SENSOR, TARGET, LAST_BOLUS, TOOLS))
        assertThat(layout.left).containsExactly(CANNULA, BATTERY_PAIR, TBR).inOrder()
        assertThat(layout.right).containsExactly(DELIVERED, SENSOR, TARGET).inOrder()
        assertThat(layout.rows).containsExactly(listOf(LAST_BOLUS, TOOLS))
    }

    @Test
    fun `0 and 1 pill`() {
        assertThat(layoutGlassPills(emptyList())).isEqualTo(GlassPillLayout(emptyList(), emptyList(), emptyList()))
        assertThat(layoutGlassPills(listOf(TOOLS))).isEqualTo(GlassPillLayout(listOf(TOOLS), emptyList(), emptyList()))
    }

    @Test
    fun `odd and even counts - the columns differ by at most one, left first`() {
        for (n in 1..6) {
            val layout = layoutGlassPills(GlassSlot.entries.take(n))
            assertThat(layout.left.size - layout.right.size).isIn(listOf(0, 1))
            assertThat(layout.rows).isEmpty()
        }
    }

    @Test
    fun `more pills than the slots beside the ring - full rows first, the last row may be shorter`() {
        val layout = layoutGlassPills(GlassSlot.entries.toList())
        assertThat(layout.left).hasSize(3)
        assertThat(layout.right).hasSize(3)
        assertThat(layout.rows.map { it.size }).containsExactly(3, 3, 2).inOrder()
    }

    @Test
    fun `no pill is lost or doubled, and the order stays`() {
        val shown = GlassSlot.entries.toList()
        val layout = layoutGlassPills(shown)
        assertThat(layout.left + layout.right + layout.rows.flatten()).containsExactlyElementsIn(shown).inOrder()
    }

    @Test
    fun `removing one pill keeps the others in the same order`() {
        val all = GlassSlot.entries.toList()
        val without = all - SENSOR
        val layout = layoutGlassPills(without)
        assertThat(layout.left + layout.right + layout.rows.flatten()).containsExactlyElementsIn(without).inOrder()
    }
}
