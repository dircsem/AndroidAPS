package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.core.interfaces.pump.MedLinkStopOrigin
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import kotlin.math.max
import kotlin.math.roundToLong

sealed class MedLinkStopDecision {

    object Send : MedLinkStopDecision() {

        override fun toString() = "Send"
    }

    /** Put an `M` in front of the stop and decide again with its answer. */
    object ReadStatusFirst : MedLinkStopDecision() {

        override fun toString() = "ReadStatusFirst"
    }

    /** Keep the stop queued until [time] (bolus still being delivered). */
    data class DeferUntil(val time: Long) : MedLinkStopDecision()

    /** `M` cannot be read: do not stop (basal keeps running, which is safer than aborting a bolus). */
    data class Alarm(val reason: String) : MedLinkStopDecision()
}

/** T1: decides whether a stop may be sent now. Automatic stops never interrupt a bolus. */
object MedLinkStopGate {

    private const val RECENT_MS = 10 * MedLinkBolusTracker.MINUTE

    fun decide(origin: MedLinkStopOrigin, tracker: MedLinkBolusTracker, pumpState: MedLinkPumpRunningState, pumpStateAt: Long, now: Long): MedLinkStopDecision {
        if (origin == MedLinkStopOrigin.USER) return MedLinkStopDecision.Send

        val request = tracker.current
        if (request?.alarmed == true && !request.state.isFinal || tracker.consecutiveUnknownReads >= MedLinkBolusTracker.MAX_UNREADABLE_FOR_STOP)
            return MedLinkStopDecision.Alarm("bolus status unknown, automatic stop held")

        val snapshot = tracker.latestSnapshot
        if (request != null && !request.state.isFinal && request.state != MedLinkBolusState.QUEUED) {
            if (request.state == MedLinkBolusState.DELIVERING && snapshot != null && snapshot.readAt >= request.sentAt) {
                val delivered = snapshot.deliveredAmount?.takeIf { snapshot.inProgress } ?: snapshot.lastBolusAmount
                return deferOrRead(snapshot.readAt + remainingMs(request.requested - delivered), now)
            }
            return MedLinkStopDecision.ReadStatusFirst
        }

        if (snapshot != null && snapshot.inProgress && now - snapshot.readAt < RECENT_MS) {
            val programmed = snapshot.squareBolusAmount?.takeIf { it > 0.0 } ?: snapshot.lastBolusAmount
            return deferOrRead(snapshot.readAt + remainingMs(programmed - (snapshot.deliveredAmount ?: 0.0)), now)
        }

        if (pumpState == MedLinkPumpRunningState.Bolusing && now - pumpStateAt < RECENT_MS &&
            (snapshot == null || snapshot.readAt < pumpStateAt)
        ) return MedLinkStopDecision.ReadStatusFirst

        return MedLinkStopDecision.Send
    }

    private fun remainingMs(units: Double): Long =
        (max(0.0, units) * MedLinkBolusTracker.MS_PER_UNIT).roundToLong() + MedLinkBolusTracker.DELIVERY_MARGIN_MS

    private fun deferOrRead(until: Long, now: Long): MedLinkStopDecision =
        if (now < until) MedLinkStopDecision.DeferUntil(until) else MedLinkStopDecision.ReadStatusFirst
}
