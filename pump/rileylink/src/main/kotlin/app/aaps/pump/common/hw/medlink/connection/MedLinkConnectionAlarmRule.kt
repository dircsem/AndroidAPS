package app.aaps.pump.common.hw.medlink.connection

import app.aaps.pump.common.hw.medlink.bolus.MedLinkClock

/**
 * T20: when to alarm because the pump cannot be reached through the MedLink. Plain rule, no Android: the caller
 * (MedLinkBLE / the plugin) reports each connection result and carries out the returned [Action] (sound, torch,
 * notification). Not thread safe by itself: call it from one thread (the BLE handler).
 *
 * - A failure is "pump no response" after OK+CONN, or no answer to OK+CONN in time. Any good pump answer is a success.
 * - [Settings.threshold] failures in a row (default 4) raise the alarm once per outage.
 * - OK ([onAcknowledged]): no new alarm until the pump is reached again. Silence ([onSilenced]): the alarm comes
 *   back after [SILENCE_MS] if the pump is still not reached.
 * - The pump answers again: the alarm stops and "connection restored" is shown once (not after OK).
 * - No alarm while the user has disconnected the pump on purpose, or with the setting off.
 *
 * @param settings read at every call, so a change of the setting works at once
 * @param pumpDisconnectedOnPurpose true while AAPS "disconnect pump" (running mode) is active
 */
class MedLinkConnectionAlarmRule(
    private val clock: MedLinkClock,
    private val settings: () -> Settings,
    private val pumpDisconnectedOnPurpose: () -> Boolean
) {

    /**
     * @param enabled MedLink setting: the alarm on or off
     * @param threshold failed connections in a row before the alarm
     */
    data class Settings(val enabled: Boolean, val threshold: Int)

    sealed class Action {

        /** Nothing to do. */
        data object None : Action()

        /** Start the alarm (sound, light, notification). */
        data class Raise(val failures: Int, val outageStart: Long) : Action()

        /** Stop the alarm without a message (setting turned off, pump disconnected on purpose). */
        data object Stop : Action()

        /** The pump answers again: stop the alarm and show "pump connection restored" once. */
        data object Restored : Action()
    }

    private enum class State { IDLE, ALARMING, SILENCED, ACKNOWLEDGED }

    private var state = State.IDLE
    private var failures = 0
    private var outageStart = 0L
    private var silencedUntil = 0L

    /** Failed connections in a row (for the log). */
    val failureCount: Int get() = failures

    /** Time of the first failure of this outage (T21); 0 when there is no failure. */
    val outageStartTime: Long get() = if (failures > 0) outageStart else 0L

    fun onFailure(): Action {
        if (failures == 0) outageStart = clock.now()
        failures++
        return evaluate()
    }

    fun onSuccess(): Action {
        val wasAlarming = state == State.ALARMING || state == State.SILENCED
        failures = 0
        state = State.IDLE
        return if (wasAlarming) Action.Restored else Action.None
    }

    /** OK pressed: no new alarm for this outage. */
    fun onAcknowledged() {
        if (state == State.ALARMING || state == State.SILENCED) state = State.ACKNOWLEDGED
    }

    /** Silence pressed: no sound and light for [SILENCE_MS]. */
    fun onSilenced() {
        if (state != State.ALARMING) return
        state = State.SILENCED
        silencedUntil = clock.now() + SILENCE_MS
    }

    /** Call regularly (for example every minute) so the alarm comes back after the silence, also without a new try. */
    fun onTick(): Action = evaluate()

    private fun evaluate(): Action {
        val s = settings()
        if (!s.enabled || pumpDisconnectedOnPurpose()) {
            // count again from 0 afterwards: a pump still not reached after the reconnect alarms again
            val wasActive = state == State.ALARMING || state == State.SILENCED
            state = State.IDLE
            failures = 0
            return if (wasActive) Action.Stop else Action.None
        }
        return when (state) {
            State.IDLE         ->
                if (failures >= s.threshold.coerceAtLeast(1)) {
                    state = State.ALARMING
                    Action.Raise(failures, outageStart)
                } else Action.None

            State.SILENCED     ->
                if (clock.now() >= silencedUntil) {
                    state = State.ALARMING
                    Action.Raise(failures, outageStart)
                } else Action.None

            State.ALARMING     -> Action.None
            State.ACKNOWLEDGED -> Action.None
        }
    }

    companion object {

        const val DEFAULT_THRESHOLD = 4
        const val SILENCE_MS = 15 * 60_000L
    }
}
