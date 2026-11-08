package app.aaps.pump.common.hw.medlink.bolus

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

fun interface Clock {

    fun now(): Long
}

enum class BolusOrigin { LOOP, MANUAL }

enum class BolusState {

    QUEUED, SENT, CONFIRMED, DELIVERING,

    /** Connection dropped or `M` said "not started": waiting for a fresh `M`. */
    UNRESOLVED,

    /** `M` could not tell. Keeps new boluses blocked (T8). */
    UNKNOWN,

    DELIVERED, PARTIAL, FAILED;

    val isFinal: Boolean get() = this == DELIVERED || this == PARTIAL || this == FAILED
}

/** Pump state as answered by the `A` (StopStartPump) command. */
enum class PumpAnswerState { RUNNING, SUSPENDED, BOLUSING }

sealed class BolusAction {

    object None : BolusAction() {

        override fun toString() = "None"
    }

    /** Re-send the bolus. Only produced after two `M` answers proved it was not started. */
    object Resend : BolusAction() {

        override fun toString() = "Resend"
    }

    data class ReadStatusAt(val time: Long) : BolusAction()
    data class Record(val amount: Double, val pumpTime: Long) : BolusAction()
    data class Fail(val reason: String) : BolusAction()
    data class Alarm(val reason: String) : BolusAction()
    data class AskUser(val snapshot: BolusStatusSnapshot?) : BolusAction()
}

class BolusRequest(
    val id: String,
    val origin: BolusOrigin,
    val requested: Double,
    val createdAt: Long
) {

    var state: BolusState = BolusState.QUEUED
        internal set
    var sentAt: Long = 0L
        internal set
    var baseline: BolusStatusSnapshot? = null
        internal set

    /** Number of times the bolus command was written to the pump. */
    var attempts: Int = 0
        internal set
    var appliedTotal: Double = 0.0
        internal set
    var lastSnapshot: BolusStatusSnapshot? = null
        internal set
    var unknownCount: Int = 0
        internal set
    var alarmed: Boolean = false
        internal set

    /** First "not started" answer seen, waiting for a fresh `M` to confirm it. */
    internal var notStartedSeenAt: Long? = null
    internal var awaitingUser: Boolean = false

    val expectedEndAt: Long
        get() = sentAt + (requested * BolusTracker.MS_PER_UNIT).roundToLong() + BolusTracker.DELIVERY_MARGIN_MS

    override fun toString() =
        "BolusRequest(id=$id, origin=$origin, requested=$requested, state=$state, attempts=$attempts, applied=$appliedTotal)"
}

/**
 * The one bolus in flight (T9). Every event returns the action MedLinkBLE must carry out.
 *
 * Safety rules (T7):
 *  - a pump confirmation never finishes a bolus; only `M` / `A` answers do
 *  - a bolus is re-sent only after two consecutive `M` answers show it was not started
 *  - at most [maxResend] re-sends, stale loop boluses are failed, manual boluses ask the user
 *  - each pump bolus (keyed by its pump time) is recorded once; applied total never exceeds the request
 */
class BolusTracker(
    private val clock: Clock,
    private val strict: Boolean = false,
    private val maxResend: Int = 1,
    private val staleLoopBolusMs: Long = 5 * MINUTE,
    private val log: (String) -> Unit
) {

    companion object {

        const val MINUTE = 60_000L
        const val MS_PER_UNIT = 40_000L
        const val DELIVERY_MARGIN_MS = 15_000L
        const val UNKNOWN_RETRY_MS = 30_000L
        const val MAX_UNKNOWN = 3
        const val MAX_UNRESOLVED_MS = 10 * MINUTE

        private val allowed: Map<BolusState, Set<BolusState>> = mapOf(
            BolusState.QUEUED to setOf(BolusState.SENT, BolusState.FAILED),
            BolusState.SENT to setOf(
                BolusState.CONFIRMED, BolusState.UNRESOLVED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL,
                BolusState.FAILED, BolusState.UNKNOWN
            ),
            BolusState.CONFIRMED to setOf(
                BolusState.UNRESOLVED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL, BolusState.FAILED,
                BolusState.UNKNOWN
            ),
            BolusState.DELIVERING to setOf(
                BolusState.UNRESOLVED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL, BolusState.UNKNOWN
            ),
            BolusState.UNRESOLVED to setOf(
                BolusState.SENT, BolusState.UNRESOLVED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL,
                BolusState.FAILED, BolusState.UNKNOWN
            ),
            BolusState.UNKNOWN to setOf(
                BolusState.UNKNOWN, BolusState.UNRESOLVED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL,
                BolusState.FAILED
            ),
        )
    }

    var current: BolusRequest? = null
        private set

    /** Latest valid `M` answer, whether or not a bolus is in flight. */
    var latestSnapshot: BolusStatusSnapshot? = null
        private set

    /** Consecutive `M` reads that could not be parsed. */
    var consecutiveUnknownReads: Int = 0
        private set

    private var lastSentAt: Long = 0L
    private val recordedPumpTimes = LinkedHashSet<Long>()

    @Synchronized
    fun canSendNewBolus(): Boolean = current?.state?.isFinal ?: true

    /** A bolus was written and is not resolved yet: `M` must be read before any stop/start or new bolus. */
    @Synchronized
    fun hasUnresolved(): Boolean = current?.let { !it.state.isFinal && it.state != BolusState.QUEUED } ?: false

    @Synchronized
    fun resendCount(): Int = max(0, (current?.attempts ?: 0) - 1)

    /** Pump time has minute resolution: two equal boluses in the same minute could not be told apart. */
    @Synchronized
    fun nextSendAllowedAt(): Long = if (lastSentAt == 0L) 0L else lastSentAt + MINUTE

    @Synchronized
    fun onQueued(id: String, origin: BolusOrigin, requested: Double): BolusRequest? {
        if (!canSendNewBolus()) {
            log("BOLUS[$id] rejected: previous bolus ${current?.id} unresolved (${current?.state})")
            return null
        }
        return BolusRequest(id, origin, requested, clock.now()).also {
            current = it
            log("BOLUS[$id] queued origin=$origin requested=$requested")
        }
    }

    @Synchronized
    fun onBaseline(snapshot: BolusStatusSnapshot?) {
        val r = current ?: return
        if (r.state != BolusState.QUEUED) return
        r.baseline = snapshot
        if (snapshot != null) latestSnapshot = snapshot
        log("BOLUS[${r.id}] baseline ${snapshot?.let { "${it.lastBolusAmount}U@${it.lastBolusTime}" } ?: "none"} raw=${snapshot?.raw}")
    }

    @Synchronized
    fun onWritten(): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state == BolusState.UNRESOLVED && r.attempts > maxResend) return illegal(r, BolusState.SENT)
        val bad = transition(r, BolusState.SENT) ?: run {
            r.sentAt = clock.now()
            lastSentAt = r.sentAt
            r.attempts++
            r.notStartedSeenAt = null
            r.awaitingUser = false
            log("BOLUS[${r.id}] written attempt=${r.attempts}")
            return BolusAction.ReadStatusAt(r.expectedEndAt)
        }
        return bad
    }

    /**
     * The pump/MedLink refused the bolus (e.g. "pump suspend state").
     * @return true when the dose was never written (request failed, a new request may be sent),
     *         false when it was written: the outcome is unclear and must be verified with `M`.
     */
    @Synchronized
    fun onRejectedBeforeWrite(): Boolean {
        val r = current ?: return true
        if (r.state == BolusState.QUEUED) {
            fail(r, "rejected before the dose was written")
            return true
        }
        if (!r.state.isFinal && r.state != BolusState.UNRESOLVED) transition(r, BolusState.UNRESOLVED)
        return false
    }

    /**
     * The tracked executor left the queues (user stop, max tries, error): a request still QUEUED was never
     * written, so it fails and new boluses are allowed again. A written one is left to `M`.
     * @return true when the request was failed here.
     */
    @Synchronized
    fun onDropped(reason: String): Boolean {
        val r = current ?: return false
        if (r.state != BolusState.QUEUED) return false
        fail(r, "dropped before the dose was written: $reason")
        return true
    }

    @Synchronized
    fun onConfirmed(): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state == BolusState.CONFIRMED) return BolusAction.None
        return transition(r, BolusState.CONFIRMED) ?: BolusAction.ReadStatusAt(r.expectedEndAt)
    }

    @Synchronized
    fun onDisconnect(): BolusAction {
        val r = current ?: return BolusAction.None
        return when (r.state) {
            BolusState.SENT, BolusState.CONFIRMED, BolusState.DELIVERING ->
                transition(r, BolusState.UNRESOLVED) ?: BolusAction.ReadStatusAt(clock.now())

            BolusState.UNRESOLVED, BolusState.UNKNOWN                    -> BolusAction.ReadStatusAt(clock.now())
            else                                                         -> BolusAction.None
        }
    }

    @Synchronized
    fun onPumpState(state: PumpAnswerState): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state.isFinal || r.state == BolusState.QUEUED) return BolusAction.None
        log("BOLUS[${r.id}] pump state $state")
        return when (state) {
            PumpAnswerState.BOLUSING  ->
                if (r.state == BolusState.SENT || r.state == BolusState.CONFIRMED)
                    transition(r, BolusState.DELIVERING) ?: BolusAction.ReadStatusAt(r.expectedEndAt)
                else BolusAction.None

            // a stop may have aborted the bolus (T3): read what was delivered right away
            PumpAnswerState.SUSPENDED ->
                (if (r.state != BolusState.UNRESOLVED) transition(r, BolusState.UNRESOLVED) else null)
                    ?: BolusAction.ReadStatusAt(clock.now())

            PumpAnswerState.RUNNING   -> BolusAction.None
        }
    }

    @Synchronized
    fun onStatus(snapshot: BolusStatusSnapshot?): BolusAction {
        if (snapshot == null) consecutiveUnknownReads++ else {
            consecutiveUnknownReads = 0
            latestSnapshot = snapshot
        }
        val r = current ?: return BolusAction.None
        if (r.state.isFinal || r.state == BolusState.QUEUED) return BolusAction.None
        r.lastSnapshot = snapshot ?: r.lastSnapshot
        val now = clock.now()
        val outcome = BolusStatusInterpreter.interpret(snapshot, BolusCheck(r.requested, r.sentAt, r.baseline, r.expectedEndAt), now)
        log("BOLUS[${r.id}] M outcome=${outcome.type} delivered=${outcome.deliveredAmount} requested=${r.requested} ${outcome.reason} raw=${snapshot?.raw}")

        return when (outcome.type) {
            BolusOutcome.Type.DELIVERED   -> record(r, BolusState.DELIVERED, outcome.deliveredAmount, outcome.pumpBolusTime!!)
            BolusOutcome.Type.PARTIAL     -> record(r, BolusState.PARTIAL, outcome.deliveredAmount, outcome.pumpBolusTime!!)
            BolusOutcome.Type.DELIVERING  -> {
                r.unknownCount = 0
                if (r.state != BolusState.DELIVERING) transition(r, BolusState.DELIVERING)?.let { return it }
                BolusAction.ReadStatusAt(now + remainingMs(r.requested, outcome.deliveredAmount))
            }

            BolusOutcome.Type.NOT_STARTED -> notStarted(r, snapshot!!, now)
            BolusOutcome.Type.UNKNOWN     -> unknown(r, now)
        }
    }

    @Synchronized
    fun onUserResendAnswer(resend: Boolean): BolusAction {
        val r = current ?: return BolusAction.None
        if (!r.awaitingUser) return BolusAction.None
        r.awaitingUser = false
        log("BOLUS[${r.id}] user answered resend=$resend")
        return if (resend) BolusAction.Resend else fail(r, "user declined resend")
    }

    /** T8: user enters what the pump history shows to unblock a bolus stuck as unknown. */
    @Synchronized
    fun resolveManually(amount: Double, pumpTime: Long): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state.isFinal) return BolusAction.None
        log("BOLUS[${r.id}] resolved manually amount=$amount")
        if (amount <= 0.0) return fail(r, "resolved manually: not delivered")
        val state = if (abs(amount - r.requested) <= BolusStatusInterpreter.TOLERANCE) BolusState.DELIVERED else BolusState.PARTIAL
        return record(r, state, amount, pumpTime)
    }

    private fun remainingMs(requested: Double, delivered: Double): Long =
        (max(0.0, requested - delivered) * MS_PER_UNIT).roundToLong() + DELIVERY_MARGIN_MS

    private fun record(r: BolusRequest, state: BolusState, amount: Double, pumpTime: Long): BolusAction {
        transition(r, state)?.let { return it }
        if (!recordedPumpTimes.add(pumpTime)) {
            log("BOLUS[${r.id}] pump bolus @$pumpTime already recorded")
            return BolusAction.None
        }
        if (r.appliedTotal + amount > r.requested + BolusStatusInterpreter.TOLERANCE) {
            return alarm(r, "applied ${r.appliedTotal + amount} U exceeds requested ${r.requested} U")
        }
        r.appliedTotal += amount
        log("BOLUS[${r.id}] record amount=$amount pumpTime=$pumpTime appliedTotal=${r.appliedTotal}")
        return BolusAction.Record(amount, pumpTime)
    }

    private fun notStarted(r: BolusRequest, snapshot: BolusStatusSnapshot, now: Long): BolusAction {
        r.unknownCount = 0
        if (snapshot.inProgress) {
            // the pump is busy with another bolus: wait for it to end, this is not a failed attempt
            val programmed = snapshot.squareBolusAmount?.takeIf { it > 0.0 } ?: snapshot.lastBolusAmount
            log("BOLUS[${r.id}] pump busy with another bolus ($programmed U), waiting")
            return BolusAction.ReadStatusAt(now + remainingMs(programmed, snapshot.deliveredAmount ?: 0.0))
        }
        val firstSeen = r.notStartedSeenAt
        if (firstSeen == null || r.state != BolusState.UNRESOLVED) {
            // guard: confirm with a fresh M on the same connection before any re-send
            if (r.state != BolusState.UNRESOLVED) transition(r, BolusState.UNRESOLVED)?.let { return it }
            r.notStartedSeenAt = snapshot.readAt
            return BolusAction.ReadStatusAt(now)
        }
        if (snapshot.readAt < firstSeen) return BolusAction.ReadStatusAt(now)
        return when {
            // without a baseline "not started" relies on the pump clock matching the phone clock: never re-send
            r.baseline == null                                                 -> {
                transition(r, BolusState.FAILED)?.let { return it }
                alarm(r, "bolus ${r.requested} U not seen in pump history and no baseline to compare - check the pump")
            }


            r.origin == BolusOrigin.LOOP && now - r.createdAt > staleLoopBolusMs -> fail(r, "stale")
            r.attempts > maxResend                                             -> {
                transition(r, BolusState.FAILED)?.let { return it }
                alarm(r, "bolus ${r.requested} U not delivered after ${r.attempts} attempts")
            }

            r.origin == BolusOrigin.MANUAL                                     -> {
                r.awaitingUser = true
                log("BOLUS[${r.id}] asking user to confirm resend")
                BolusAction.AskUser(snapshot)
            }

            else                                                               -> {
                log("BOLUS[${r.id}] resend (attempt ${r.attempts + 1})")
                BolusAction.Resend
            }
        }
    }

    private fun unknown(r: BolusRequest, now: Long): BolusAction {
        r.unknownCount++
        if (r.state != BolusState.UNKNOWN) transition(r, BolusState.UNKNOWN)?.let { return it }
        if (!r.alarmed && (r.unknownCount >= MAX_UNKNOWN || now - r.sentAt > MAX_UNRESOLVED_MS)) {
            return alarm(r, "bolus ${r.requested} U unconfirmed, last M: ${r.lastSnapshot?.let { "${it.lastBolusAmount}U delivered=${it.deliveredAmount}" } ?: "none"}")
        }
        return BolusAction.ReadStatusAt(now + UNKNOWN_RETRY_MS)
    }

    private fun fail(r: BolusRequest, reason: String): BolusAction =
        transition(r, BolusState.FAILED) ?: run {
            log("BOLUS[${r.id}] failed: $reason")
            BolusAction.Fail(reason)
        }

    private fun alarm(r: BolusRequest, reason: String): BolusAction {
        r.alarmed = true
        log("BOLUS[${r.id}] ALARM $reason")
        return BolusAction.Alarm(reason)
    }

    /** @return null when the transition is legal, otherwise the action to carry out (alarm). */
    private fun transition(r: BolusRequest, to: BolusState): BolusAction? {
        if (allowed[r.state]?.contains(to) != true) return illegal(r, to)
        log("BOLUS[${r.id}] state ${r.state}->$to requested=${r.requested} delivered=${r.appliedTotal} attempt=${r.attempts}")
        r.state = to
        return null
    }

    private fun illegal(r: BolusRequest, to: BolusState): BolusAction {
        val msg = "illegal transition ${r.state}->$to"
        if (strict) throw IllegalStateException("BOLUS[${r.id}] $msg")
        return alarm(r, msg)
    }
}
