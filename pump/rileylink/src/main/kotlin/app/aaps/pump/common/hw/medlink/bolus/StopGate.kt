package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.core.interfaces.pump.StopOrigin
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import kotlin.math.max
import kotlin.math.roundToLong

sealed class StopDecision {

    object Send : StopDecision() {

        override fun toString() = "Send"
    }

    /** Put an `M` in front of the stop and decide again with its answer. */
    object ReadStatusFirst : StopDecision() {

        override fun toString() = "ReadStatusFirst"
    }

    /** Keep the stop queued until [time] (bolus still being delivered). */
    data class DeferUntil(val time: Long) : StopDecision()

    /** `M` cannot be read: do not stop (basal keeps running, which is safer than aborting a bolus). */
    data class Alarm(val reason: String) : StopDecision()
}

/** T1: decides whether a stop may be sent now. Automatic stops never interrupt a bolus. */
object StopGate {

    private const val RECENT_MS = 10 * BolusTracker.MINUTE

    fun decide(origin: StopOrigin, tracker: BolusTracker, pumpState: MedLinkPumpRunningState, pumpStateAt: Long, now: Long): StopDecision {
        if (origin == StopOrigin.USER) return StopDecision.Send

        val request = tracker.current
        if (request?.alarmed == true && !request.state.isFinal || tracker.consecutiveUnknownReads >= BolusTracker.MAX_UNREADABLE_FOR_STOP)
            return StopDecision.Alarm("bolus status unknown, automatic stop held")

        val snapshot = tracker.latestSnapshot
        if (request != null && !request.state.isFinal && request.state != BolusState.QUEUED) {
            if (request.state == BolusState.DELIVERING && snapshot != null && snapshot.readAt >= request.sentAt) {
                val delivered = snapshot.deliveredAmount?.takeIf { snapshot.inProgress } ?: snapshot.lastBolusAmount
                return deferOrRead(snapshot.readAt + remainingMs(request.requested - delivered), now)
            }
            return StopDecision.ReadStatusFirst
        }

        if (snapshot != null && snapshot.inProgress && now - snapshot.readAt < RECENT_MS) {
            val programmed = snapshot.squareBolusAmount?.takeIf { it > 0.0 } ?: snapshot.lastBolusAmount
            return deferOrRead(snapshot.readAt + remainingMs(programmed - (snapshot.deliveredAmount ?: 0.0)), now)
        }

        if (pumpState == MedLinkPumpRunningState.Bolusing && now - pumpStateAt < RECENT_MS &&
            (snapshot == null || snapshot.readAt < pumpStateAt)
        ) return StopDecision.ReadStatusFirst

        return StopDecision.Send
    }

    private fun remainingMs(units: Double): Long =
        (max(0.0, units) * BolusTracker.MS_PER_UNIT).roundToLong() + BolusTracker.DELIVERY_MARGIN_MS

    private fun deferOrRead(until: Long, now: Long): StopDecision =
        if (now < until) StopDecision.DeferUntil(until) else StopDecision.ReadStatusFirst
}
