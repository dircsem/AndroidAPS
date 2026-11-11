package app.aaps.pump.medtronic.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MedLinkPostBolusMinBasalTest {

    private val min = 60_000L
    private val now = 1_800_000_000_000L
    private val settings = MedLinkPostBolusMinBasal.Settings(minPercent = 50, windowMinutes = 180, minDelta = 2.0)

    private fun rate(
        requested: Double = 0.0, s: MedLinkPostBolusMinBasal.Settings = settings, bolusAt: Long? = now - 60 * min,
        delta: Double? = 5.0, bg: Double? = 150.0, target: Double = 100.0
    ) = MedLinkPostBolusMinBasal.decide(requested, 1.2, s, bolusAt, now, delta, bg, target).rate

    @Test
    fun `zero temp basal after a manual bolus with BG rising is raised to the minimum`() {
        assertEquals(0.6, rate(0.0), 1e-9)
        assertEquals(0.6, rate(0.3), 1e-9)
    }

    @Test
    fun `requested at or above the minimum is kept`() {
        assertEquals(0.6, rate(0.6), 1e-9)
        assertEquals(2.0, rate(2.0), 1e-9)
    }

    @Test
    fun `off with 0 percent`() {
        assertEquals(0.0, rate(0.0, s = settings.copy(minPercent = 0)), 1e-9)
    }

    @Test
    fun `only inside the window after the manual bolus`() {
        assertEquals(0.6, rate(bolusAt = now - 180 * min), 1e-9)
        assertEquals(0.0, rate(bolusAt = now - 181 * min), 1e-9)
        assertEquals(0.0, rate(bolusAt = null), 1e-9)
        assertEquals(0.6, rate(bolusAt = now - 200 * min, s = settings.copy(windowMinutes = 240)), 1e-9)
    }

    @Test
    fun `only when the 5 min delta is above the configured one`() {
        assertEquals(0.0, rate(delta = 2.0), 1e-9)
        assertEquals(0.6, rate(delta = 2.1), 1e-9)
        assertEquals(0.0, rate(delta = -1.0), 1e-9)
    }

    @Test
    fun `not below target and not without glucose data`() {
        assertEquals(0.0, rate(bg = 99.0), 1e-9)
        assertEquals(0.6, rate(bg = 100.0), 1e-9)
        assertEquals(0.0, rate(bg = null), 1e-9)
        assertEquals(0.0, rate(delta = null), 1e-9)
    }
}
