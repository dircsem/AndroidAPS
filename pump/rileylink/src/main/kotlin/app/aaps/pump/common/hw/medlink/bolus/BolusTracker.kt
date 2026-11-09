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

    /** `M` could not tell. Keeps new boluses blocked until `M` resolves it or fails [BolusTracker.MAX_STATUS_FAILURES] times. */
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
    origin: BolusOrigin,
    requested: Double,
    val createdAt: Long
) {

    /** 4i: becomes MANUAL when a manual bolus joins it (never re-sent without the user). */
    var origin: BolusOrigin = origin
        internal set

    /** 4i: grows when another bolus joins it before the pump received it. */
    var requested: Double = requested
        internal set

    var state: BolusState = BolusState.QUEUED
        internal set
    var sentAt: Long = 0L
        internal set
    var baseline: BolusStatusSnapshot? = null
        internal set

    /**
     * Number of times the bolus was tried: every time its executor is posted ([BolusTracker.onTry]), also when
     * the connection drops before the dose is written, or every write when no try was counted ([BolusTracker.onWritten]).
     */
    var attempts: Int = 0
        internal set

    /** `M` rounds in a row that did not resolve the bolus (invalid command, no answer, unreadable, ambiguous). */
    var statusFailures: Int = 0
        internal set
    var appliedTotal: Double = 0.0
        internal set
    var lastSnapshot: BolusStatusSnapshot? = null
        internal set
    var unknownCount: Int = 0
        internal set
    var alarmed: Boolean = false
        internal set

    /** An attempt was counted by [BolusTracker.onTry] and not yet consumed by a write. */
    internal var tryCounted: Boolean = false

    /** The MedLink confirmed the current attempt: a "not started" `M` after it is a command without effect (lock). */
    internal var confirmedThisAttempt: Boolean = false

    /** First "not started" answer seen, waiting for a fresh `M` to confirm it. */
    internal var notStartedSeenAt: Long? = null
    internal var awaitingUser: Boolean = false

    /** `M` proved the bolus was not started and a re-send was decided: other boluses may join it (4i). */
    internal var resendAllowed: Boolean = false

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
 *  - at most [maxAttempts] tries (a dropped connection counts too), manual boluses ask the user
 *  - [MAX_STATUS_FAILURES] `M` rounds in a row without an answer that resolves the bolus fail it (alarm)
 *  - each pump bolus (keyed by its pump time) is recorded once; applied total never exceeds the request
 */
class BolusTracker(
    private val clock: Clock,
    private val strict: Boolean = false,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val log: (String) -> Unit
) {

    /** The MedLink confirmed the bolus and `M` proved it was not delivered (pump lock evidence). */
    var onConfirmedWithoutEffect: ((String) -> Unit)? = null

    /** `M` shows the bolus being delivered or delivered: the pump accepts commands. */
    var onPumpEffect: ((String) -> Unit)? = null

    companion object {

        const val MINUTE = 60_000L
        const val MS_PER_UNIT = 40_000L
        const val DELIVERY_MARGIN_MS = 15_000L
        const val UNKNOWN_RETRY_MS = 30_000L
        const val MAX_ATTEMPTS = 5
        const val MAX_STATUS_FAILURES = 5

        /** Unreadable `M` answers in a row that hold an automatic stop (StopGate). */
        const val MAX_UNREADABLE_FOR_STOP = 3

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
                BolusState.UNRESOLVED, BolusState.DELIVERING, BolusState.DELIVERED, BolusState.PARTIAL, BolusState.UNKNOWN,
                BolusState.FAILED
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

    /** Tries left for the tracked bolus (0 when there is none or it is final). */
    @Synchronized
    fun triesLeft(): Int = current?.takeIf { !it.state.isFinal }?.let { max(0, maxAttempts - it.attempts) } ?: 0

    /**
     * The tracked bolus executor is posted (first step): one more try, whether or not the dose gets written
     * (the connection may drop before). Not counted for a written bolus that `M` did not allow to re-send.
     */
    @Synchronized
    fun onTry() {
        val r = current ?: return
        if (r.state != BolusState.QUEUED && !(r.state == BolusState.UNRESOLVED && r.resendAllowed)) return
        r.attempts++
        r.tryCounted = true
        log("BOLUS[${r.id}] try ${r.attempts}/$maxAttempts")
    }

    @Synchronized
    fun onWritten(): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state == BolusState.UNRESOLVED && (!r.resendAllowed || r.attempts > maxAttempts)) return illegal(r, BolusState.SENT)
        val bad = transition(r, BolusState.SENT) ?: run {
            r.sentAt = clock.now()
            lastSentAt = r.sentAt
            if (!r.tryCounted) r.attempts++
            r.tryCounted = false
            r.confirmedThisAttempt = false
            r.statusFailures = 0
            r.notStartedSeenAt = null
            r.awaitingUser = false
            r.resendAllowed = false
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

    /**
     * 4i: another bolus joined the tracked one, which the pump has not received: QUEUED (dose not written)
     * or UNRESOLVED right before a re-send that `M` allowed. [total] is the new amount written to the pump.
     * @return false when the request may not change (written and not proved undelivered, or final).
     */
    @Synchronized
    fun onMerged(total: Double, manual: Boolean): Boolean {
        val r = current ?: return false
        if (r.state != BolusState.QUEUED && !(r.state == BolusState.UNRESOLVED && r.resendAllowed)) {
            log("BOLUS[${r.id}] merge refused in state ${r.state}")
            return false
        }
        log("BOLUS[${r.id}] merged: requested ${r.requested} -> $total${if (manual) " (manual joined)" else ""}")
        r.requested = total
        if (manual) r.origin = BolusOrigin.MANUAL
        return true
    }

    /**
     * 4j: [snapshot] shows a bolus in progress that is not the tracked one (given on the pump, a square
     * bolus, or a bolus that started before ours was sent): the user is told what the pump delivers.
     */
    @Synchronized
    fun isOtherBolusInProgress(snapshot: BolusStatusSnapshot): Boolean {
        if (!snapshot.inProgress) return false
        val r = current ?: return true
        if (r.state.isFinal || r.state == BolusState.QUEUED) return true
        val baseline = r.baseline
        return if (baseline != null) snapshot.lastBolusTime <= baseline.lastBolusTime
        else snapshot.lastBolusTime < r.sentAt - 2 * MINUTE
    }

    @Synchronized
    fun onConfirmed(): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state == BolusState.CONFIRMED) return BolusAction.None
        return transition(r, BolusState.CONFIRMED) ?: run {
            r.confirmedThisAttempt = true
            BolusAction.ReadStatusAt(r.expectedEndAt)
        }
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

        if (outcome.type != BolusOutcome.Type.UNKNOWN) r.statusFailures = 0
        if (outcome.type == BolusOutcome.Type.DELIVERED || outcome.type == BolusOutcome.Type.PARTIAL || outcome.type == BolusOutcome.Type.DELIVERING)
            onPumpEffect?.invoke("bolus ${r.id} ${outcome.type}")
        return when (outcome.type) {
            BolusOutcome.Type.DELIVERED   -> record(r, BolusState.DELIVERED, outcome.deliveredAmount, outcome.pumpBolusTime!!)
            BolusOutcome.Type.PARTIAL     -> record(r, BolusState.PARTIAL, outcome.deliveredAmount, outcome.pumpBolusTime!!)
            BolusOutcome.Type.DELIVERING  -> {
                r.unknownCount = 0
                if (r.state != BolusState.DELIVERING) transition(r, BolusState.DELIVERING)?.let { return it }
                BolusAction.ReadStatusAt(now + remainingMs(r.requested, outcome.deliveredAmount))
            }

            BolusOutcome.Type.NOT_STARTED -> notStarted(r, snapshot!!, now)
            BolusOutcome.Type.UNKNOWN     -> unknown(r, now, outcome.reason)
        }
    }

    /**
     * An `M` round for the bolus in flight ended without an answer: "invalid command", no answer before the
     * connection dropped, etc. Counts toward [MAX_STATUS_FAILURES]; reads `M` again right away until then.
     */
    @Synchronized
    fun onStatusFailed(reason: String): BolusAction {
        val r = current ?: return BolusAction.None
        if (r.state.isFinal || r.state == BolusState.QUEUED) return BolusAction.None
        return statusFailed(r, reason) ?: BolusAction.ReadStatusAt(clock.now())
    }

    @Synchronized
    fun onUserResendAnswer(resend: Boolean): BolusAction {
        val r = current ?: return BolusAction.None
        if (!r.awaitingUser) return BolusAction.None
        r.awaitingUser = false
        log("BOLUS[${r.id}] user answered resend=$resend")
        if (resend) r.resendAllowed = true
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
        if (r.confirmedThisAttempt) {
            // the MedLink confirmed it and the pump did not deliver it: pump lock evidence (or a one-off failure)
            r.confirmedThisAttempt = false
            onConfirmedWithoutEffect?.invoke("bolus ${r.requested} U confirmed, not delivered (attempt ${r.attempts})")
        }
        return when {
            // without a baseline "not started" relies on the pump clock matching the phone clock: never re-send
            r.baseline == null                                                 -> {
                transition(r, BolusState.FAILED)?.let { return it }
                alarm(r, "bolus ${r.requested} U not seen in pump history and no baseline to compare - check the pump")
            }

            r.attempts >= maxAttempts                                          -> {
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
                r.resendAllowed = true
                BolusAction.Resend
            }
        }
    }

    private fun unknown(r: BolusRequest, now: Long, reason: String): BolusAction {
        r.unknownCount++
        if (r.state != BolusState.UNKNOWN) transition(r, BolusState.UNKNOWN)?.let { return it }
        return statusFailed(r, reason.ifEmpty { "M unreadable" }) ?: BolusAction.ReadStatusAt(now + UNKNOWN_RETRY_MS)
    }

    /** @return the final action when the `M` failures reached [MAX_STATUS_FAILURES], null to read `M` again. */
    private fun statusFailed(r: BolusRequest, reason: String): BolusAction? {
        r.statusFailures++
        log("BOLUS[${r.id}] M failure ${r.statusFailures}/$MAX_STATUS_FAILURES: $reason")
        if (r.statusFailures < MAX_STATUS_FAILURES) return null
        transition(r, BolusState.FAILED)?.let { return it }
        return alarm(
            r, "bolus ${r.requested} U could not be confirmed: M failed ${r.statusFailures} times ($reason), last M: " +
                "${r.lastSnapshot?.let { "${it.lastBolusAmount}U delivered=${it.deliveredAmount}" } ?: "none"}. " +
                "It may have been delivered and was not recorded: check the pump history before bolusing again."
        )
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
        if (to != BolusState.UNRESOLVED) r.resendAllowed = false
        return null
    }

    private fun illegal(r: BolusRequest, to: BolusState): BolusAction {
        val msg = "illegal transition ${r.state}->$to"
        if (strict) throw IllegalStateException("BOLUS[${r.id}] $msg")
        return alarm(r, msg)
    }
}
