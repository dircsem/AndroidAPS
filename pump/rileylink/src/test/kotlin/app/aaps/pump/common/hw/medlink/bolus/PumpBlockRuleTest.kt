package app.aaps.pump.common.hw.medlink.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PumpBlockRuleTest {

    private val t0 = 1_000_000_000L
    private val limit = PumpBlockRule.UNRESOLVED_BLOCK_MS

    @Test
    fun `an unresolved bolus blocks after the limit or once the tracker alarmed`() {
        assertFalse(PumpBlockRule.bolusUnresolved(BolusState.UNRESOLVED, t0, alarmed = false, now = t0 + limit))
        assertTrue(PumpBlockRule.bolusUnresolved(BolusState.UNRESOLVED, t0, alarmed = false, now = t0 + limit + 1))
        assertTrue(PumpBlockRule.bolusUnresolved(BolusState.UNKNOWN, t0, alarmed = false, now = t0 + limit + 1))
        assertTrue(PumpBlockRule.bolusUnresolved(BolusState.UNKNOWN, t0, alarmed = true, now = t0 + 1))
    }

    @Test
    fun `a bolus being delivered or resolved never blocks`() {
        for (state in listOf(BolusState.QUEUED, BolusState.SENT, BolusState.CONFIRMED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL, BolusState.FAILED, null))
            assertFalse(PumpBlockRule.bolusUnresolved(state, t0, alarmed = true, now = t0 + 10 * limit), "$state")
    }

    @Test
    fun `start refused wins over an unresolved bolus`() {
        assertNull(PumpBlockRule.decide(bolusUnresolved = false, startRefused = false))
        assertEquals(PumpBlock.UNREACHABLE, PumpBlockRule.decide(bolusUnresolved = true, startRefused = false))
        assertEquals(PumpBlock.NOT_ACCEPTING_COMMANDS, PumpBlockRule.decide(bolusUnresolved = false, startRefused = true))
        assertEquals(PumpBlock.NOT_ACCEPTING_COMMANDS, PumpBlockRule.decide(bolusUnresolved = true, startRefused = true))
    }

    @Test
    fun `queued boluses are held (not sent, not expired) only while the pump refuses state changes`() {
        assertTrue(PumpBlockRule.holdsQueuedBoluses(PumpBlock.NOT_ACCEPTING_COMMANDS))
        assertFalse(PumpBlockRule.holdsQueuedBoluses(PumpBlock.UNREACHABLE))
        assertFalse(PumpBlockRule.holdsQueuedBoluses(null))
    }
}
