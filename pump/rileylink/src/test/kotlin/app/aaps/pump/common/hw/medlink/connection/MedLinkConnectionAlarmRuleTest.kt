package app.aaps.pump.common.hw.medlink.connection

import app.aaps.pump.common.hw.medlink.bolus.MedLinkClock
import app.aaps.pump.common.hw.medlink.connection.MedLinkConnectionAlarmRule.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MedLinkConnectionAlarmRuleTest {

    private class FakeClock(var t: Long) : MedLinkClock {

        override fun now() = t
    }

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private lateinit var clock: FakeClock
    private lateinit var rule: MedLinkConnectionAlarmRule
    private var settings = MedLinkConnectionAlarmRule.Settings(enabled = true, threshold = 4)
    private var disconnectedOnPurpose = false

    @BeforeEach
    fun setup() {
        clock = FakeClock(t0)
        settings = MedLinkConnectionAlarmRule.Settings(enabled = true, threshold = 4)
        disconnectedOnPurpose = false
        rule = MedLinkConnectionAlarmRule(clock, { settings }, { disconnectedOnPurpose })
    }

    private fun fail(times: Int): List<Action> = (1..times).map {
        val a = rule.onFailure()
        clock.t += min
        a
    }

    @Test
    fun `3 failures - no alarm`() {
        assertEquals(List(3) { Action.None }, fail(3))
    }

    @Test
    fun `4 failures - alarm once, with the count and the start of the outage`() {
        val actions = fail(6)
        assertEquals(Action.Raise(failures = 4, outageStart = t0), actions[3])
        assertEquals(listOf(Action.None, Action.None), actions.drop(4))
    }

    @Test
    fun `a success in between resets the count`() {
        fail(3)
        assertEquals(Action.None, rule.onSuccess())
        assertEquals(List(3) { Action.None }, fail(3))
        assertEquals(Action.Raise(failures = 4, outageStart = t0 + 3 * min), rule.onFailure())
    }

    @Test
    fun `the connection comes back - alarm stopped and restored once`() {
        fail(4)
        assertEquals(Action.Restored, rule.onSuccess())
        assertEquals(Action.None, rule.onSuccess())
    }

    @Test
    fun `the pump disconnected on purpose - no alarm`() {
        disconnectedOnPurpose = true
        assertEquals(List(6) { Action.None }, fail(6))
    }

    @Test
    fun `the setting off - no alarm`() {
        settings = settings.copy(enabled = false)
        assertEquals(List(6) { Action.None }, fail(6))
    }

    @Test
    fun `the threshold follows the setting`() {
        settings = settings.copy(threshold = 2)
        assertEquals(Action.Raise(failures = 2, outageStart = t0), fail(2)[1])
    }

    @Test
    fun `OK - no new alarm in the same outage, and no restored message`() {
        fail(4)
        rule.onAcknowledged()
        assertEquals(List(5) { Action.None }, fail(5))
        assertEquals(Action.None, rule.onTick())
        assertEquals(Action.None, rule.onSuccess())
    }

    @Test
    fun `OK - a new outage alarms again`() {
        fail(4)
        rule.onAcknowledged()
        rule.onSuccess()
        val start = clock.t
        assertEquals(Action.Raise(failures = 4, outageStart = start), fail(4)[3])
    }

    @Test
    fun `silence - the alarm again after 15 min if still failing`() {
        fail(4)
        rule.onSilenced()
        assertEquals(Action.None, rule.onFailure())
        clock.t = t0 + 4 * min + MedLinkConnectionAlarmRule.SILENCE_MS - 1
        assertEquals(Action.None, rule.onTick())
        clock.t = t0 + 4 * min + MedLinkConnectionAlarmRule.SILENCE_MS
        assertEquals(Action.Raise(failures = 5, outageStart = t0), rule.onTick())
    }

    @Test
    fun `silence - no alarm again if the connection came back`() {
        fail(4)
        rule.onSilenced()
        assertEquals(Action.Restored, rule.onSuccess())
        clock.t += MedLinkConnectionAlarmRule.SILENCE_MS
        assertEquals(Action.None, rule.onTick())
    }

    @Test
    fun `the pump disconnected on purpose during an alarm - the alarm stops`() {
        fail(4)
        disconnectedOnPurpose = true
        assertEquals(Action.Stop, rule.onTick())
        assertEquals(Action.None, rule.onFailure())
    }

    @Test
    fun `after the pump is connected again on purpose, a pump still not reached alarms after the threshold again`() {
        fail(4)
        disconnectedOnPurpose = true
        rule.onTick()
        disconnectedOnPurpose = false
        val actions = fail(4)
        assertEquals(List(3) { Action.None }, actions.take(3))
        assertEquals(Action.Raise::class, actions[3]::class)
    }
}
