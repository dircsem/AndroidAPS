package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState

/**
 * T2 / T13: a queued StartPump / StopPump may be dropped as "already done" only when the state was
 * read from the pump after the last start/stop was sent, is less than 60 s old, and matches.
 * Never on Bolusing or Unknow.
 */
object MedLinkStartStopSkipRule {

    private const val MAX_AGE_MS = 60_000L

    fun shouldSkip(command: MedLinkCommandType, state: MedLinkPumpRunningState, stateAt: Long, lastStartStopSent: Long, now: Long): Boolean {
        val matches = command == MedLinkCommandType.StartPump && state == MedLinkPumpRunningState.Running ||
            command == MedLinkCommandType.StopPump && state == MedLinkPumpRunningState.Suspended
        return matches && stateAt > lastStartStopSent && now - stateAt < MAX_AGE_MS
    }
}
