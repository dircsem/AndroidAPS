package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.core.interfaces.pump.MedLinkStopOrigin
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MedLinkStopGateTest {

    private class FakeClock(var t: Long) : MedLinkClock {

        override fun now() = t
    }

    private val min = 60_000L
    private val t0 = MedLinkBolusFixtures.pumpTime("10-06-21 17:45") + 10_000
    private lateinit var clock: FakeClock
    private lateinit var tracker: MedLinkBolusTracker
    private val baseline = MedLinkBolusFixtures.snapshot(0.3, MedLinkBolusFixtures.pumpTime("10-06-21 17:30"), readAt = t0 - 2_000)
    private val newTime = MedLinkBolusFixtures.pumpTime("10-06-21 17:45")

    @BeforeEach
    fun setup() {
        clock = FakeClock(t0)
        tracker = MedLinkBolusTracker(clock, strict = true) {}
    }

    private fun decide(origin: MedLinkStopOrigin = MedLinkStopOrigin.AUTOMATIC, state: MedLinkPumpRunningState = MedLinkPumpRunningState.Running, stateAt: Long = t0 - min) =
        MedLinkStopGate.decide(origin, tracker, state, stateAt, clock.now())

    private fun bolus(amount: Double) {
        tracker.onQueued("b1", MedLinkBolusOrigin.LOOP, amount)
        tracker.onBaseline(baseline)
        tracker.onWritten()
    }

    @Test
    fun `no bolus and pump not bolusing - send`() {
        assertEquals(MedLinkStopDecision.Send, decide())
    }

    @Test
    fun `user stop is always sent, even mid bolus`() {
        bolus(1.0)
        assertEquals(MedLinkStopDecision.Send, decide(MedLinkStopOrigin.USER, MedLinkPumpRunningState.Bolusing, t0))
    }

    @Test
    fun `bolus sent or confirmed - read M first`() {
        bolus(1.0)
        assertEquals(MedLinkStopDecision.ReadStatusFirst, decide())
        tracker.onConfirmed()
        assertEquals(MedLinkStopDecision.ReadStatusFirst, decide())
    }

    @Test
    fun `M says delivering 0_6 of 1_0 - defer for the remaining dose`() {
        bolus(1.0)
        clock.t += 20_000
        tracker.onStatus(MedLinkBolusFixtures.snapshot(1.0, newTime, square = 1.0, delivered = 0.6, readAt = clock.now()))
        assertEquals(MedLinkStopDecision.DeferUntil(clock.now() + 16_000 + 15_000), decide())
    }

    @Test
    fun `after the deferral the gate reads M again`() {
        bolus(1.0)
        tracker.onStatus(MedLinkBolusFixtures.snapshot(1.0, newTime, square = 1.0, delivered = 0.6, readAt = clock.now()))
        clock.t += 5 * min
        assertEquals(MedLinkStopDecision.ReadStatusFirst, decide())
    }

    @Test
    fun `M says delivered - send`() {
        bolus(1.0)
        clock.t += 2 * min
        tracker.onStatus(MedLinkBolusFixtures.snapshot(1.0, newTime, readAt = clock.now()))
        assertEquals(MedLinkStopDecision.Send, decide())
    }

    @Test
    fun `M unknown three times - no stop, alarm`() {
        bolus(1.0)
        repeat(3) { tracker.onStatus(null) }
        assertInstanceOf(MedLinkStopDecision.Alarm::class.java, decide())
    }

    @Test
    fun `pump answered bolusing without a tracked bolus - read M first`() {
        assertEquals(MedLinkStopDecision.ReadStatusFirst, decide(state = MedLinkPumpRunningState.Bolusing, stateAt = t0 - 5_000))
    }

    @Test
    fun `pump answered bolusing but a later M shows nothing in progress - send`() {
        tracker.onStatus(MedLinkBolusFixtures.snapshot(0.5, newTime, readAt = t0))
        assertEquals(MedLinkStopDecision.Send, decide(state = MedLinkPumpRunningState.Bolusing, stateAt = t0 - 5_000))
    }

    @Test
    fun `untracked bolus in progress in the latest M - defer`() {
        tracker.onStatus(MedLinkBolusFixtures.snapshot(2.0, newTime, square = 2.0, delivered = 1.0, readAt = t0))
        assertEquals(MedLinkStopDecision.DeferUntil(t0 + 40_000 + 15_000), decide())
    }

    @Test
    fun `untracked M unknown three times - alarm instead of looping`() {
        repeat(3) { tracker.onStatus(null) }
        assertInstanceOf(MedLinkStopDecision.Alarm::class.java, decide(state = MedLinkPumpRunningState.Bolusing, stateAt = t0 - 5_000))
    }
}
