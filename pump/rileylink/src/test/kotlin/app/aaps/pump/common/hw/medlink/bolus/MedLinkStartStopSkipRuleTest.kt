package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkStartStopSkipRuleTest {

    private val now = 1_000_000L

    private fun skip(cmd: MedLinkCommandType, state: MedLinkPumpRunningState, stateAt: Long, lastSent: Long) =
        MedLinkStartStopSkipRule.shouldSkip(cmd, state, stateAt, lastSent, now)

    @Test
    fun `stop is skipped when the pump said suspended after the last stop was sent`() {
        assertTrue(skip(MedLinkCommandType.StopPump, MedLinkPumpRunningState.Suspended, now - 10_000, now - 20_000))
    }

    @Test
    fun `stop is not skipped when the pump is bolusing (T2)`() {
        assertFalse(skip(MedLinkCommandType.StopPump, MedLinkPumpRunningState.Bolusing, now - 10_000, now - 20_000))
    }

    @Test
    fun `start is not skipped when the pump is bolusing`() {
        assertFalse(skip(MedLinkCommandType.StartPump, MedLinkPumpRunningState.Bolusing, now - 10_000, now - 20_000))
    }

    @Test
    fun `start is not skipped when running was read before the stop was sent (T13)`() {
        assertFalse(skip(MedLinkCommandType.StartPump, MedLinkPumpRunningState.Running, now - 30_000, now - 20_000))
    }

    @Test
    fun `start is skipped when running was read after the last stop or start`() {
        assertTrue(skip(MedLinkCommandType.StartPump, MedLinkPumpRunningState.Running, now - 10_000, now - 20_000))
    }

    @Test
    fun `state older than 60 s never skips`() {
        assertFalse(skip(MedLinkCommandType.StopPump, MedLinkPumpRunningState.Suspended, now - 61_000, now - 120_000))
    }

    @Test
    fun `unknown state never skips`() {
        assertFalse(skip(MedLinkCommandType.StopPump, MedLinkPumpRunningState.Unknow, now - 1_000, 0))
    }

    @Test
    fun `mismatching state never skips`() {
        assertFalse(skip(MedLinkCommandType.StopPump, MedLinkPumpRunningState.Running, now - 1_000, 0))
        assertFalse(skip(MedLinkCommandType.StartPump, MedLinkPumpRunningState.Suspended, now - 1_000, 0))
    }
}
