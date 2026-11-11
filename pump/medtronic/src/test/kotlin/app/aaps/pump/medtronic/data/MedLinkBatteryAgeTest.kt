package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MedLinkBatteryAgeTest {

    private val hour = 3_600_000L
    private val life = 240 // OverviewBageCritical default (10 days)

    @Test
    fun `new battery is full`() {
        assertEquals(100, MedLinkBatteryAge.percentLeft(0, life))
        assertEquals(4, MedLinkBatteryAge.iconLevel(100))
    }

    @Test
    fun `yellow from 25 percent left`() {
        assertEquals(25, MedLinkBatteryAge.percentLeft(180 * hour, life))
        assertEquals(1, MedLinkBatteryAge.iconLevel(25))
    }

    @Test
    fun `red and empty at the critical age and after it`() {
        assertEquals(0, MedLinkBatteryAge.percentLeft(240 * hour, life))
        assertEquals(0, MedLinkBatteryAge.percentLeft(300 * hour, life))
        assertEquals(0, MedLinkBatteryAge.iconLevel(0))
    }

    @Test
    fun `any charge left shows one bar`() {
        assertEquals(1, MedLinkBatteryAge.iconLevel(1))
        assertEquals(2, MedLinkBatteryAge.iconLevel(26))
        assertEquals(4, MedLinkBatteryAge.iconLevel(76))
    }

    @Test
    fun `clock behind the change time counts as new, no life configured as empty`() {
        assertEquals(100, MedLinkBatteryAge.percentLeft(-hour, life))
        assertEquals(0, MedLinkBatteryAge.percentLeft(hour, 0))
    }

    @Test
    fun `icon follows the color - red is empty, yellow is a quarter`() {
        for (percent in 0..100) {
            val icon = MedLinkBatteryAge.iconLevel(percent)
            when {
                percent <= MedLinkBatteryAge.URGENT_PERCENT -> assertEquals(0, icon, "red at $percent %")
                percent <= MedLinkBatteryAge.WARN_PERCENT   -> assertEquals(1, icon, "yellow at $percent %")
                else                                        -> assert(icon >= 2) { "default color at $percent % shows $icon" }
            }
        }
    }
}
