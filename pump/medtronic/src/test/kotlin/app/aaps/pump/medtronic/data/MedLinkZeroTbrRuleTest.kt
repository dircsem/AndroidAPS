package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkZeroTbrRule.PumpRan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkZeroTbrRuleTest {

    private fun start(
        batteryInfoIsAge: Boolean = true,
        batteryAgeHours: Long? = 400,
        criticalHours: Int = 336,
        failures: Int = 4,
        threshold: Int = 4,
        alreadyStarted: Boolean = false,
        alarmEnabled: Boolean = true,
        disconnectedOnPurpose: Boolean = false
    ) = MedLinkZeroTbrRule.shouldStart(batteryInfoIsAge, batteryAgeHours, criticalHours, failures, threshold, alreadyStarted, alarmEnabled, disconnectedOnPurpose)

    @Test
    fun `old battery (red) and the failure count reached - 0 % TBR`() {
        assertTrue(start())
    }

    @Test
    fun `battery age not red - no TBR`() {
        assertFalse(start(batteryAgeHours = 335))
    }

    @Test
    fun `battery age exactly at the critical threshold - red, TBR`() {
        assertTrue(start(batteryAgeHours = 336))
    }

    @Test
    fun `level mode - no TBR`() {
        assertFalse(start(batteryInfoIsAge = false))
    }

    @Test
    fun `no battery change event - age unknown, no TBR`() {
        assertFalse(start(batteryAgeHours = null))
    }

    @Test
    fun `fewer failures than the threshold - no TBR`() {
        assertFalse(start(failures = 3))
    }

    @Test
    fun `already started for this outage - not twice`() {
        assertFalse(start(alreadyStarted = true))
    }

    @Test
    fun `connection alarm setting off - no TBR (same counter and setting)`() {
        assertFalse(start(alarmEnabled = false))
    }

    @Test
    fun `pump disconnected on purpose - no TBR`() {
        assertFalse(start(disconnectedOnPurpose = true))
    }

    // facts for the question on reconnect

    @Test
    fun `reservoir went down by about the scheduled basal - the pump probably ran`() {
        assertEquals(PumpRan.PROBABLY_YES, MedLinkZeroTbrRule.pumpRan(reservoirBefore = 100.0, reservoirNow = 98.6, scheduledUnits = 1.5))
    }

    @Test
    fun `reservoir did not go down - the pump probably stopped`() {
        assertEquals(PumpRan.PROBABLY_NO, MedLinkZeroTbrRule.pumpRan(reservoirBefore = 100.0, reservoirNow = 100.0, scheduledUnits = 1.5))
    }

    @Test
    fun `reservoir went down by half the basal - not clear`() {
        assertEquals(PumpRan.UNKNOWN, MedLinkZeroTbrRule.pumpRan(reservoirBefore = 100.0, reservoirNow = 99.4, scheduledUnits = 1.5))
    }

    @Test
    fun `reservoir unknown or a very short outage - not clear`() {
        assertEquals(PumpRan.UNKNOWN, MedLinkZeroTbrRule.pumpRan(reservoirBefore = null, reservoirNow = 98.0, scheduledUnits = 1.5))
        assertEquals(PumpRan.UNKNOWN, MedLinkZeroTbrRule.pumpRan(reservoirBefore = 100.0, reservoirNow = null, scheduledUnits = 1.5))
        assertEquals(PumpRan.UNKNOWN, MedLinkZeroTbrRule.pumpRan(reservoirBefore = 100.0, reservoirNow = 100.0, scheduledUnits = 0.2))
    }

    @Test
    fun `reservoir went up (refilled) - not clear`() {
        assertEquals(PumpRan.UNKNOWN, MedLinkZeroTbrRule.pumpRan(reservoirBefore = 20.0, reservoirNow = 150.0, scheduledUnits = 1.5))
    }
}
