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
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.RESERVOIR_LEVEL
import app.aaps.plugins.main.general.dashboard.glass.GlassSlot.RESERVOIR_PAIR
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
            RESERVOIR_PAIR, CANNULA, BATTERY_PAIR, TBR, DELIVERED, SENSOR, LOOP, ACTIVITY, TARGET, LAST_BOLUS, IOB, LAST_CARBS, TOOLS
        ).inOrder()
    }

    @Test
    fun `T29 reservoir pill - shown only when selected, first in the pump group`() {
        assertThat(shownGlassSlots(listOf(GlassPillId.PUMP_RESERVOIR), hasMedLinkBattery = false, hasDelivered = false)).containsExactly(RESERVOIR, TOOLS).inOrder()
        assertThat(shownGlassSlots(listOf(GlassPillId.RESERVOIR_LEVEL, GlassPillId.CANNULA), hasMedLinkBattery = false, hasDelivered = false))
            .containsExactly(RESERVOIR_LEVEL, CANNULA, TOOLS).inOrder()
    }

    @Test
    fun `insulin age and reservoir level both selected - one pill`() {
        assertThat(shownGlassSlots(listOf(GlassPillId.RESERVOIR_LEVEL, GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA), hasMedLinkBattery = false, hasDelivered = false))
            .containsExactly(RESERVOIR_PAIR, CANNULA, TOOLS).inOrder()
    }

    // ---- where they go ----

    @Test
    fun `sketch A with the battery pill - 8 pills - 4 and 4 beside the ring, no row (T30)`() {
        val layout = layoutGlassPills(listOf(CANNULA, BATTERY_PAIR, TBR, DELIVERED, SENSOR, TARGET, LAST_BOLUS, TOOLS))
        assertThat(layout.left).containsExactly(CANNULA, BATTERY_PAIR, TBR, DELIVERED).inOrder()
        assertThat(layout.right).containsExactly(SENSOR, TARGET, LAST_BOLUS, TOOLS).inOrder()
        assertThat(layout.rows).isEmpty()
    }

    @Test
    fun `T30 sketch - all pills with MedLink, insulin age and reservoir in one pill`() {
        val shown = listOf(RESERVOIR_PAIR, CANNULA, BATTERY_PAIR, TBR, DELIVERED, SENSOR, LOOP, ACTIVITY, TARGET, LAST_BOLUS, IOB, LAST_CARBS, TOOLS)
        val layout = layoutGlassPills(shown)
        assertThat(layout.left).containsExactly(RESERVOIR_PAIR, CANNULA, BATTERY_PAIR, TBR).inOrder()
        assertThat(layout.right).containsExactly(DELIVERED, SENSOR, LOOP, ACTIVITY).inOrder()
        assertThat(layout.rows).containsExactly(listOf(TARGET, LAST_BOLUS, IOB), listOf(LAST_CARBS, TOOLS)).inOrder()
    }

    @Test
    fun `T30 sketch - default selection with MedLink, 7 pills - 4 and 3 beside the ring`() {
        val layout = layoutGlassPills(listOf(RESERVOIR, CANNULA, BATTERY_PAIR, SENSOR, LOOP, ACTIVITY, TOOLS))
        assertThat(layout.left).containsExactly(RESERVOIR, CANNULA, BATTERY_PAIR, SENSOR).inOrder()
        assertThat(layout.right).containsExactly(LOOP, ACTIVITY, TOOLS).inOrder()
        assertThat(layout.rows).isEmpty()
    }

    @Test
    fun `T30 - 7, 8, 9, 11 and 12 pills - beside the ring first, then full rows of 3`() {
        val expected = mapOf(7 to Triple(4, 3, listOf<Int>()), 8 to Triple(4, 4, listOf()), 9 to Triple(4, 4, listOf(1)),
            11 to Triple(4, 4, listOf(3)), 12 to Triple(4, 4, listOf(3, 1)))
        for ((n, e) in expected) {
            val shown = GlassSlot.entries.take(n)
            val layout = layoutGlassPills(shown)
            assertThat(layout.left).hasSize(e.first)
            assertThat(layout.right).hasSize(e.second)
            assertThat(layout.rows.map { it.size }).containsExactlyElementsIn(e.third).inOrder()
            assertThat(layout.left + layout.right + layout.rows.flatten()).containsExactlyElementsIn(shown).inOrder()
        }
    }

    @Test
    fun `T30 - 6 pills or less - the same as before (3 and 3 at most, no row)`() {
        for (n in 1..6) {
            val layout = layoutGlassPills(GlassSlot.entries.take(n))
            assertThat(layout.left.size).isAtMost(3)
            assertThat(layout.right.size).isAtMost(3)
            assertThat(layout.rows).isEmpty()
        }
    }

    @Test
    fun `0 and 1 pill`() {
        assertThat(layoutGlassPills(emptyList())).isEqualTo(GlassPillLayout(emptyList(), emptyList(), emptyList()))
        assertThat(layoutGlassPills(listOf(TOOLS))).isEqualTo(GlassPillLayout(listOf(TOOLS), emptyList(), emptyList()))
    }

    @Test
    fun `odd and even counts - the columns differ by at most one, left first`() {
        for (n in 1..8) {
            val layout = layoutGlassPills(GlassSlot.entries.take(n))
            assertThat(layout.left.size - layout.right.size).isIn(listOf(0, 1))
            assertThat(layout.rows).isEmpty()
        }
    }

    @Test
    fun `more pills than the slots beside the ring - full rows first, the last row may be shorter`() {
        val layout = layoutGlassPills(GlassSlot.entries.toList())
        assertThat(layout.left).hasSize(4)
        assertThat(layout.right).hasSize(4)
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
