package app.aaps.pump.common.hw.medlink.bolus

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

fun interface MedLinkClock {

    fun now(): Long
}

enum class MedLinkBolusOrigin { LOOP, MANUAL }

enum class MedLinkBolusState {

    QUEUED, SENT, CONFIRMED, DELIVERING,

    /** Connection dropped or `M` said "not started": waiting for a fresh `M`. */
    UNRESOLVED,

    /** `M` could not tell. Keeps new boluses blocked until `M` resolves it or fails [MedLinkBolusTracker.MAX_STATUS_FAILURES] times. */
    UNKNOWN,

    DELIVERED, PARTIAL, FAILED;

    val isFinal: Boolean get() = this == DELIVERED || this == PARTIAL || this == FAILED
}

/** Pump state as answered by the `A` (StopStartPump) command. */
enum class MedLinkPumpAnswerState { RUNNING, SUSPENDED, BOLUSING }

sealed class MedLinkBolusAction {

    object None : MedLinkBolusAction() {

        override fun toString() = "None"
    }

    /** Re-send the bolus. Only produced after two `M` answers proved it was not started. */
    object Resend : MedLinkBolusAction() {

        override fun toString() = "Resend"
    }

    data class ReadStatusAt(val time: Long) : MedLinkBolusAction()
    data class Record(val amount: Double, val pumpTime: Long) : MedLinkBolusAction()
    data class Fail(val reason: String) : MedLinkBolusAction()
    data class Alarm(val reason: String) : MedLinkBolusAction()
    data class AskUser(val snapshot: MedLinkBolusStatusSnapshot?) : MedLinkBolusAction()
}

class MedLinkBolusRequest(
    val id: String,
    origin: MedLinkBolusOrigin,
    requested: Double,
    val createdAt: Long
) {

    /** 4i: becomes MANUAL when a manual bolus joins it (never re-sent without the user). */
    var origin: MedLinkBolusOrigin = origin
        internal set

    /** 4i: grows when another bolus joins it before the pump received it. */
    var requested: Double = requested
        internal set

    var state: MedLinkBolusState = MedLinkBolusState.QUEUED
        internal set
    var sentAt: Long = 0L
        internal set
    var baseline: MedLinkBolusStatusSnapshot? = null
        internal set

    /**
     * Number of times the bolus was tried: every time its executor is posted ([MedLinkBolusTracker.onTry]), also when
     * the connection drops before the dose is written, or every write when no try was counted ([MedLinkBolusTracker.onWritten]).
     */
    var attempts: Int = 0
        internal set

    /** `M` rounds in a row that did not resolve the bolus (invalid command, no answer, unreadable, ambiguous). */
    var statusFailures: Int = 0
        internal set
    var appliedTotal: Double = 0.0
        internal set
    var lastSnapshot: MedLinkBolusStatusSnapshot? = null
        internal set
    var unknownCount: Int = 0
        internal set
    var alarmed: Boolean = false
        internal set

    /** An attempt was counted by [MedLinkBolusTracker.onTry] and not yet consumed by a write. */
    internal var tryCounted: Boolean = false

    /** The MedLink confirmed the current attempt: a "not started" `M` after it is a command without effect (lock). */
    internal var confirmedThisAttempt: Boolean = false

    /** First "not started" answer seen, waiting for a fresh `M` to confirm it. */
    internal var notStartedSeenAt: Long? = null
    internal var awaitingUser: Boolean = false

    /**
     * What the pump took, from the bolus answer ("pump is bolusing / last bolus: Xu"): with a bad link the pump
     * may get less than was sent. Null until known. Never more than [requested].
     */
    var programmed: Double? = null
        internal set

    /** `M` proved the bolus was not started and a re-send was decided: other boluses may join it (4i). */
    internal var resendAllowed: Boolean = false

    val expectedEndAt: Long
        get() = sentAt + ((programmed ?: requested) * MedLinkBolusTracker.MS_PER_UNIT).roundToLong() + MedLinkBolusTracker.DELIVERY_MARGIN_MS

    override fun toString() =
        "MedLinkBolusRequest(id=$id, origin=$origin, requested=$requested, state=$state, attempts=$attempts, applied=$appliedTotal)"
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
class MedLinkBolusTracker(
    private val clock: MedLinkClock,
    private val strict: Boolean = false,
    /** 4aa: tries per bolus; the pump plugin passes the "command attempts" setting. */
    private val maxAttemptsProvider: () -> Int = { MAX_ATTEMPTS },
    private val log: (String) -> Unit
) {

    /** The MedLink confirmed the bolus and `M` proved it was not delivered (pump lock evidence). */
    var onConfirmedWithoutEffect: ((String) -> Unit)? = null

    /** `M` shows the bolus being delivered or delivered: the pump accepts commands. */
    var onPumpEffect: ((String) -> Unit)? = null

    /** The bolus in flight is being delivered: amount delivered so far (from `M`, 0.0 when unknown). */
    var onProgress: ((MedLinkBolusRequest, Double) -> Unit)? = null

    companion object {

        const val MINUTE = 60_000L
        const val MS_PER_UNIT = 40_000L
        const val DELIVERY_MARGIN_MS = 15_000L
        const val UNKNOWN_RETRY_MS = 30_000L

        /** A manual bolus is followed with `M` this often while delivering (progress dialog); loop boluses only at the expected end. */
        const val PROGRESS_READ_MS = 15_000L

        /** Below this a bolus may be over before "bolus verification": its own pump time shows in the bolus answer. */
        const val SMALL_BOLUS = 0.3
        const val MAX_ATTEMPTS = 5
        const val MAX_STATUS_FAILURES = 5

        /** Unreadable `M` answers in a row that hold an automatic stop (MedLinkStopGate). */
        const val MAX_UNREADABLE_FOR_STOP = 3

        private val allowed: Map<MedLinkBolusState, Set<MedLinkBolusState>> = mapOf(
            MedLinkBolusState.QUEUED to setOf(MedLinkBolusState.SENT, MedLinkBolusState.FAILED),
            MedLinkBolusState.SENT to setOf(
                MedLinkBolusState.CONFIRMED, MedLinkBolusState.UNRESOLVED, MedLinkBolusState.DELIVERING, MedLinkBolusState.DELIVERED, MedLinkBolusState.PARTIAL,
                MedLinkBolusState.FAILED, MedLinkBolusState.UNKNOWN
            ),
            MedLinkBolusState.CONFIRMED to setOf(
                MedLinkBolusState.UNRESOLVED, MedLinkBolusState.DELIVERING, MedLinkBolusState.DELIVERED, MedLinkBolusState.PARTIAL, MedLinkBolusState.FAILED,
                MedLinkBolusState.UNKNOWN
            ),
            MedLinkBolusState.DELIVERING to setOf(
                MedLinkBolusState.UNRESOLVED, MedLinkBolusState.DELIVERING, MedLinkBolusState.DELIVERED, MedLinkBolusState.PARTIAL, MedLinkBolusState.UNKNOWN,
                MedLinkBolusState.FAILED
            ),
            MedLinkBolusState.UNRESOLVED to setOf(
                MedLinkBolusState.SENT, MedLinkBolusState.UNRESOLVED, MedLinkBolusState.DELIVERING, MedLinkBolusState.DELIVERED, MedLinkBolusState.PARTIAL,
                MedLinkBolusState.FAILED, MedLinkBolusState.UNKNOWN
            ),
            MedLinkBolusState.UNKNOWN to setOf(
                MedLinkBolusState.UNKNOWN, MedLinkBolusState.UNRESOLVED, MedLinkBolusState.DELIVERING, MedLinkBolusState.DELIVERED, MedLinkBolusState.PARTIAL,
                MedLinkBolusState.FAILED
            ),
        )
    }

    var current: MedLinkBolusRequest? = null
        private set

    /** Latest valid `M` answer, whether or not a bolus is in flight. */
    var latestSnapshot: MedLinkBolusStatusSnapshot? = null
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
    fun hasUnresolved(): Boolean = current?.let { !it.state.isFinal && it.state != MedLinkBolusState.QUEUED } ?: false

    @Synchronized
    fun resendCount(): Int = max(0, (current?.attempts ?: 0) - 1)

    /** Pump time has minute resolution: two equal boluses in the same minute could not be told apart. */
    @Synchronized
    fun nextSendAllowedAt(): Long = if (lastSentAt == 0L) 0L else lastSentAt + MINUTE

    @Synchronized
    fun onQueued(id: String, origin: MedLinkBolusOrigin, requested: Double): MedLinkBolusRequest? {
        if (!canSendNewBolus()) {
            log("BOLUS[$id] rejected: previous bolus ${current?.id} unresolved (${current?.state})")
            return null
        }
        return MedLinkBolusRequest(id, origin, requested, clock.now()).also {
            current = it
            log("BOLUS[$id] queued origin=$origin requested=$requested")
        }
    }

    @Synchronized
    fun onBaseline(snapshot: MedLinkBolusStatusSnapshot?) {
        val r = current ?: return
        if (r.state != MedLinkBolusState.QUEUED) return
        r.baseline = snapshot
        if (snapshot != null) latestSnapshot = snapshot
        log("BOLUS[${r.id}] baseline ${snapshot?.let { "${it.lastBolusAmount}U@${it.lastBolusTime}" } ?: "none"} raw=${snapshot?.raw}")
    }

    private fun maxAttempts(): Int = maxAttemptsProvider().coerceAtLeast(1)

    /** Tries left for the tracked bolus (0 when there is none or it is final). */
    @Synchronized
    fun triesLeft(): Int = current?.takeIf { !it.state.isFinal }?.let { max(0, maxAttempts() - it.attempts) } ?: 0

    /**
     * The tracked bolus executor is posted (first step): one more try, whether or not the dose gets written
     * (the connection may drop before). Not counted for a written bolus that `M` did not allow to re-send.
     */
    @Synchronized
    fun onTry() {
        val r = current ?: return
        if (r.state != MedLinkBolusState.QUEUED && !(r.state == MedLinkBolusState.UNRESOLVED && r.resendAllowed)) return
        r.attempts++
        r.tryCounted = true
        log("BOLUS[${r.id}] try ${r.attempts}/${maxAttempts()}")
    }

    @Synchronized
    fun onWritten(): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state == MedLinkBolusState.UNRESOLVED && (!r.resendAllowed || r.attempts > maxAttempts())) return illegal(r, MedLinkBolusState.SENT)
        val bad = transition(r, MedLinkBolusState.SENT) ?: run {
            r.sentAt = clock.now()
            lastSentAt = r.sentAt
            if (!r.tryCounted) r.attempts++
            r.tryCounted = false
            r.confirmedThisAttempt = false
            r.statusFailures = 0
            r.programmed = null
            r.notStartedSeenAt = null
            r.awaitingUser = false
            r.resendAllowed = false
            log("BOLUS[${r.id}] written attempt=${r.attempts}")
            return MedLinkBolusAction.ReadStatusAt(r.expectedEndAt)
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
        if (r.state == MedLinkBolusState.QUEUED) {
            fail(r, "rejected before the dose was written")
            return true
        }
        if (!r.state.isFinal && r.state != MedLinkBolusState.UNRESOLVED) transition(r, MedLinkBolusState.UNRESOLVED)
        return false
    }

    /** 4z: `M` allowed a re-send and the bolus was not written again yet. Writing it clears this (no double send). */
    @Synchronized
    fun resendPending(): Boolean = current?.let { it.state == MedLinkBolusState.UNRESOLVED && it.resendAllowed } == true

    /**
     * 4z: `M` proved the bolus was not started and asked for a re-send, but there is no command to send: fail it so
     * the result reaches the loop and new boluses are allowed (it stayed UNRESOLVED and every `M` asked again).
     */
    @Synchronized
    fun onResendImpossible(reason: String): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state.isFinal) return MedLinkBolusAction.None
        return fail(r, reason)
    }

    /**
     * The tracked executor left the queues (user stop, max tries, error): a request still QUEUED was never
     * written, so it fails and new boluses are allowed again. A written one is left to `M`.
     * @return true when the request was failed here.
     */
    @Synchronized
    fun onDropped(reason: String): Boolean {
        val r = current ?: return false
        if (r.state != MedLinkBolusState.QUEUED) return false
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
        if (r.state != MedLinkBolusState.QUEUED && !(r.state == MedLinkBolusState.UNRESOLVED && r.resendAllowed)) {
            log("BOLUS[${r.id}] merge refused in state ${r.state}")
            return false
        }
        log("BOLUS[${r.id}] merged: requested ${r.requested} -> $total${if (manual) " (manual joined)" else ""}")
        r.requested = total
        if (manual) r.origin = MedLinkBolusOrigin.MANUAL
        return true
    }

    /**
     * 4j: [snapshot] shows a bolus in progress that is not the tracked one (given on the pump, a square
     * bolus, or a bolus that started before ours was sent): the user is told what the pump delivers.
     */
    @Synchronized
    fun isOtherBolusInProgress(snapshot: MedLinkBolusStatusSnapshot): Boolean {
        if (!snapshot.inProgress) return false
        val r = current ?: return true
        if (r.state.isFinal || r.state == MedLinkBolusState.QUEUED) return true
        if (MedLinkBolusStatusInterpreter.isSentBolusOnSquareLine(snapshot, MedLinkBolusCheck(r.requested, r.sentAt, r.baseline, r.expectedEndAt))) return false
        val baseline = r.baseline
        return if (baseline != null) snapshot.lastBolusTime <= baseline.lastBolusTime
        else snapshot.lastBolusTime < r.sentAt - 2 * MINUTE
    }

    @Synchronized
    fun onConfirmed(): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state == MedLinkBolusState.CONFIRMED) return MedLinkBolusAction.None
        return transition(r, MedLinkBolusState.CONFIRMED) ?: run {
            r.confirmedThisAttempt = true
            MedLinkBolusAction.ReadStatusAt(r.expectedEndAt)
        }
    }

    @Synchronized
    fun onDisconnect(): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        return when (r.state) {
            MedLinkBolusState.SENT, MedLinkBolusState.CONFIRMED, MedLinkBolusState.DELIVERING ->
                transition(r, MedLinkBolusState.UNRESOLVED) ?: MedLinkBolusAction.ReadStatusAt(clock.now())

            MedLinkBolusState.UNRESOLVED, MedLinkBolusState.UNKNOWN                    -> MedLinkBolusAction.ReadStatusAt(clock.now())
            else                                                         -> MedLinkBolusAction.None
        }
    }

    @Synchronized
    fun onPumpState(state: MedLinkPumpAnswerState): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state.isFinal || r.state == MedLinkBolusState.QUEUED) return MedLinkBolusAction.None
        log("BOLUS[${r.id}] pump state $state")
        return when (state) {
            MedLinkPumpAnswerState.BOLUSING  ->
                if (r.state == MedLinkBolusState.SENT || r.state == MedLinkBolusState.CONFIRMED)
                    transition(r, MedLinkBolusState.DELIVERING) ?: MedLinkBolusAction.ReadStatusAt(r.expectedEndAt)
                else MedLinkBolusAction.None

            // a stop may have aborted the bolus (T3): read what was delivered right away
            MedLinkPumpAnswerState.SUSPENDED ->
                (if (r.state != MedLinkBolusState.UNRESOLVED) transition(r, MedLinkBolusState.UNRESOLVED) else null)
                    ?: MedLinkBolusAction.ReadStatusAt(clock.now())

            MedLinkPumpAnswerState.RUNNING   -> MedLinkBolusAction.None
        }
    }

    /**
     * The answer of the BOLUS command itself (after "bolus verification"). It already tells the outcome, so
     * no `M` is needed to check it: the pump delivering the same amount -> DELIVERING; normal state with a
     * bolus newer than the baseline -> DELIVERED; normal state with the previous bolus -> not delivered,
     * proven by the pump right after the command (no second `M`). Anything else is left to `M`.
     */
    @Synchronized
    fun onBolusAnswer(answer: MedLinkBolusCommandAnswer?): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state.isFinal || r.state == MedLinkBolusState.QUEUED) return MedLinkBolusAction.None
        val now = clock.now()
        log("BOLUS[${r.id}] bolus answer: ${answer?.let { "last ${it.lastBolusAmount}U@${it.lastBolusTime} bolusing=${it.bolusing}" } ?: "none"}")
        if (answer == null) return MedLinkBolusAction.ReadStatusAt(now)
        val time = answer.lastBolusTime
        if (answer.bolusing || time == null) {
            // no time: the pump is delivering our bolus; the amount is what it took, maybe less than sent (bad link)
            if (!answer.bolusing || answer.lastBolusAmount > r.requested + MedLinkBolusStatusInterpreter.TOLERANCE) return MedLinkBolusAction.ReadStatusAt(now)
            if (answer.lastBolusAmount < r.requested - MedLinkBolusStatusInterpreter.TOLERANCE)
                log("BOLUS[${r.id}] pump took ${answer.lastBolusAmount} U of ${r.requested} U: following it, the rest is never re-sent")
            r.programmed = answer.lastBolusAmount
            onPumpEffect?.invoke("bolus ${r.id} delivering")
            r.statusFailures = 0
            if (r.state != MedLinkBolusState.DELIVERING) transition(r, MedLinkBolusState.DELIVERING)?.let { return it }
            onProgress?.invoke(r, 0.0)
            return MedLinkBolusAction.ReadStatusAt(nextDeliveringRead(r, 0.0, now))
        }
        // a time: normally the previous bolus (not delivered); a small bolus may already be over and show its own time
        if (r.requested >= SMALL_BOLUS && time > (r.baseline?.lastBolusTime ?: Long.MAX_VALUE))
            log("BOLUS[${r.id}] bolus of ${r.requested} U already over at verification (pump time $time): unusual for its size")
        val snapshot = MedLinkBolusStatusSnapshot(
            lastBolusAmount = answer.lastBolusAmount, lastBolusTime = time, squareBolusAmount = null, deliveredAmount = null,
            pumpBolusing = false, readAt = now, raw = answer.raw
        )
        return handleStatus(snapshot, provenNotStarted = true, pumpIdle = true)
    }

    @Synchronized
    fun onStatus(snapshot: MedLinkBolusStatusSnapshot?): MedLinkBolusAction = handleStatus(snapshot, provenNotStarted = false)

    private fun nextDeliveringRead(r: MedLinkBolusRequest, delivered: Double, now: Long): Long {
        val end = now + remainingMs(r.programmed ?: r.requested, delivered)
        return if (r.origin == MedLinkBolusOrigin.MANUAL) minOf(end, now + PROGRESS_READ_MS) else end
    }

    /** @param pumpIdle the pump said "normal state" with this answer: a new bolus below the request is over (partial). */
    private fun handleStatus(snapshot: MedLinkBolusStatusSnapshot?, provenNotStarted: Boolean, pumpIdle: Boolean = false): MedLinkBolusAction {
        if (snapshot == null) consecutiveUnknownReads++ else {
            consecutiveUnknownReads = 0
            latestSnapshot = snapshot
        }
        val r = current ?: return MedLinkBolusAction.None
        if (r.state.isFinal || r.state == MedLinkBolusState.QUEUED) return MedLinkBolusAction.None
        r.lastSnapshot = snapshot ?: r.lastSnapshot
        val now = clock.now()
        val interpreted = MedLinkBolusStatusInterpreter.interpret(snapshot, MedLinkBolusCheck(r.requested, r.sentAt, r.baseline, r.expectedEndAt), now)
        val outcome = if (pumpIdle && interpreted.type == MedLinkBolusOutcome.Type.DELIVERING && interpreted.pumpBolusTime != null)
            interpreted.copy(type = MedLinkBolusOutcome.Type.PARTIAL, reason = "pump idle: bolus over below the request")
        else interpreted
        log("BOLUS[${r.id}] M outcome=${outcome.type} delivered=${outcome.deliveredAmount} requested=${r.requested} ${outcome.reason} raw=${snapshot?.raw}")

        if (outcome.type != MedLinkBolusOutcome.Type.UNKNOWN) r.statusFailures = 0
        if (outcome.type == MedLinkBolusOutcome.Type.DELIVERED || outcome.type == MedLinkBolusOutcome.Type.PARTIAL || outcome.type == MedLinkBolusOutcome.Type.DELIVERING)
            onPumpEffect?.invoke("bolus ${r.id} ${outcome.type}")
        return when (outcome.type) {
            MedLinkBolusOutcome.Type.DELIVERED   -> record(r, MedLinkBolusState.DELIVERED, outcome.deliveredAmount, outcome.pumpBolusTime!!)
            MedLinkBolusOutcome.Type.PARTIAL     -> record(r, MedLinkBolusState.PARTIAL, outcome.deliveredAmount, outcome.pumpBolusTime!!)
            MedLinkBolusOutcome.Type.DELIVERING  -> {
                r.unknownCount = 0
                if (r.state != MedLinkBolusState.DELIVERING) transition(r, MedLinkBolusState.DELIVERING)?.let { return it }
                onProgress?.invoke(r, outcome.deliveredAmount)
                MedLinkBolusAction.ReadStatusAt(nextDeliveringRead(r, outcome.deliveredAmount, now))
            }

            MedLinkBolusOutcome.Type.NOT_STARTED -> notStarted(r, snapshot!!, now, provenNotStarted)
            MedLinkBolusOutcome.Type.UNKNOWN     -> unknown(r, now, outcome.reason)
        }
    }

    /**
     * An `M` round for the bolus in flight ended without an answer: "invalid command", no answer before the
     * connection dropped, etc. Counts toward [MAX_STATUS_FAILURES]; reads `M` again right away until then.
     */
    @Synchronized
    fun onStatusFailed(reason: String): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state.isFinal || r.state == MedLinkBolusState.QUEUED) return MedLinkBolusAction.None
        return statusFailed(r, reason) ?: MedLinkBolusAction.ReadStatusAt(clock.now())
    }

    @Synchronized
    fun onUserResendAnswer(resend: Boolean): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (!r.awaitingUser) return MedLinkBolusAction.None
        r.awaitingUser = false
        log("BOLUS[${r.id}] user answered resend=$resend")
        if (resend) r.resendAllowed = true
        return if (resend) MedLinkBolusAction.Resend else fail(r, "user declined resend")
    }

    /** T8: user enters what the pump history shows to unblock a bolus stuck as unknown. */
    @Synchronized
    fun resolveManually(amount: Double, pumpTime: Long): MedLinkBolusAction {
        val r = current ?: return MedLinkBolusAction.None
        if (r.state.isFinal) return MedLinkBolusAction.None
        log("BOLUS[${r.id}] resolved manually amount=$amount")
        if (amount <= 0.0) return fail(r, "resolved manually: not delivered")
        val state = if (abs(amount - r.requested) <= MedLinkBolusStatusInterpreter.TOLERANCE) MedLinkBolusState.DELIVERED else MedLinkBolusState.PARTIAL
        return record(r, state, amount, pumpTime)
    }

    private fun remainingMs(requested: Double, delivered: Double): Long =
        (max(0.0, requested - delivered) * MS_PER_UNIT).roundToLong() + DELIVERY_MARGIN_MS

    private fun record(r: MedLinkBolusRequest, state: MedLinkBolusState, amount: Double, pumpTime: Long): MedLinkBolusAction {
        transition(r, state)?.let { return it }
        if (!recordedPumpTimes.add(pumpTime)) {
            log("BOLUS[${r.id}] pump bolus @$pumpTime already recorded")
            return MedLinkBolusAction.None
        }
        if (r.appliedTotal + amount > r.requested + MedLinkBolusStatusInterpreter.TOLERANCE) {
            return alarm(r, "applied ${r.appliedTotal + amount} U exceeds requested ${r.requested} U")
        }
        r.appliedTotal += amount
        log("BOLUS[${r.id}] record amount=$amount pumpTime=$pumpTime appliedTotal=${r.appliedTotal}")
        return MedLinkBolusAction.Record(amount, pumpTime)
    }

    /** @param proven the pump said "normal state" with the previous bolus right after the command: no fresh `M` needed. */
    private fun notStarted(r: MedLinkBolusRequest, snapshot: MedLinkBolusStatusSnapshot, now: Long, proven: Boolean = false): MedLinkBolusAction {
        r.unknownCount = 0
        if (snapshot.inProgress) {
            // the pump is busy with another bolus: wait for it to end, this is not a failed attempt
            val programmed = snapshot.squareBolusAmount?.takeIf { it > 0.0 } ?: snapshot.lastBolusAmount
            log("BOLUS[${r.id}] pump busy with another bolus ($programmed U), waiting")
            return MedLinkBolusAction.ReadStatusAt(now + remainingMs(programmed, snapshot.deliveredAmount ?: 0.0))
        }
        if (proven) {
            if (r.state != MedLinkBolusState.UNRESOLVED) transition(r, MedLinkBolusState.UNRESOLVED)?.let { return it }
            log("BOLUS[${r.id}] not delivered: the bolus answer shows the previous bolus")
        } else {
            val firstSeen = r.notStartedSeenAt
            if (firstSeen == null || r.state != MedLinkBolusState.UNRESOLVED) {
                // guard: confirm with a fresh M on the same connection before any re-send
                if (r.state != MedLinkBolusState.UNRESOLVED) transition(r, MedLinkBolusState.UNRESOLVED)?.let { return it }
                r.notStartedSeenAt = snapshot.readAt
                return MedLinkBolusAction.ReadStatusAt(now)
            }
            if (snapshot.readAt < firstSeen) return MedLinkBolusAction.ReadStatusAt(now)
        }
        if (r.confirmedThisAttempt) {
            // the MedLink confirmed it and the pump did not deliver it: pump lock evidence (or a one-off failure)
            r.confirmedThisAttempt = false
            onConfirmedWithoutEffect?.invoke("bolus ${r.requested} U confirmed, not delivered (attempt ${r.attempts})")
        }
        return when {
            // without a baseline "not started" relies on the pump clock matching the phone clock: never re-send
            r.baseline == null                                                 -> {
                transition(r, MedLinkBolusState.FAILED)?.let { return it }
                alarm(r, "bolus ${r.requested} U not seen in pump history and no baseline to compare - check the pump")
            }

            r.attempts >= maxAttempts()                                          -> {
                transition(r, MedLinkBolusState.FAILED)?.let { return it }
                alarm(r, "bolus ${r.requested} U not delivered after ${r.attempts} attempts")
            }

            r.origin == MedLinkBolusOrigin.MANUAL                                     -> {
                r.awaitingUser = true
                log("BOLUS[${r.id}] asking user to confirm resend")
                MedLinkBolusAction.AskUser(snapshot)
            }

            else                                                               -> {
                log("BOLUS[${r.id}] resend (attempt ${r.attempts + 1})")
                r.resendAllowed = true
                MedLinkBolusAction.Resend
            }
        }
    }

    private fun unknown(r: MedLinkBolusRequest, now: Long, reason: String): MedLinkBolusAction {
        r.unknownCount++
        if (r.state != MedLinkBolusState.UNKNOWN) transition(r, MedLinkBolusState.UNKNOWN)?.let { return it }
        return statusFailed(r, reason.ifEmpty { "M unreadable" }) ?: MedLinkBolusAction.ReadStatusAt(now + UNKNOWN_RETRY_MS)
    }

    /** @return the final action when the `M` failures reached [MAX_STATUS_FAILURES], null to read `M` again. */
    private fun statusFailed(r: MedLinkBolusRequest, reason: String): MedLinkBolusAction? {
        r.statusFailures++
        log("BOLUS[${r.id}] M failure ${r.statusFailures}/$MAX_STATUS_FAILURES: $reason")
        if (r.statusFailures < MAX_STATUS_FAILURES) return null
        transition(r, MedLinkBolusState.FAILED)?.let { return it }
        return alarm(
            r, "bolus ${r.requested} U could not be confirmed: M failed ${r.statusFailures} times ($reason), last M: " +
                "${r.lastSnapshot?.let { "${it.lastBolusAmount}U delivered=${it.deliveredAmount}" } ?: "none"}. " +
                "It may have been delivered and was not recorded: check the pump history before bolusing again."
        )
    }

    private fun fail(r: MedLinkBolusRequest, reason: String): MedLinkBolusAction =
        transition(r, MedLinkBolusState.FAILED) ?: run {
            log("BOLUS[${r.id}] failed: $reason")
            MedLinkBolusAction.Fail(reason)
        }

    private fun alarm(r: MedLinkBolusRequest, reason: String): MedLinkBolusAction {
        r.alarmed = true
        log("BOLUS[${r.id}] ALARM $reason")
        return MedLinkBolusAction.Alarm(reason)
    }

    /** @return null when the transition is legal, otherwise the action to carry out (alarm). */
    private fun transition(r: MedLinkBolusRequest, to: MedLinkBolusState): MedLinkBolusAction? {
        if (allowed[r.state]?.contains(to) != true) return illegal(r, to)
        log("BOLUS[${r.id}] state ${r.state}->$to requested=${r.requested} delivered=${r.appliedTotal} attempt=${r.attempts}")
        r.state = to
        if (to != MedLinkBolusState.UNRESOLVED) r.resendAllowed = false
        return null
    }

    private fun illegal(r: MedLinkBolusRequest, to: MedLinkBolusState): MedLinkBolusAction {
        val msg = "illegal transition ${r.state}->$to"
        if (strict) throw IllegalStateException("BOLUS[${r.id}] $msg")
        return alarm(r, msg)
    }
}
