package app.aaps.pump.common.hw.medlink.bolus

/** 4j: why the driver reports the pump as blocked (new boluses, TBRs and profiles are refused). */
enum class PumpBlock {

    /** A sent bolus cannot be resolved by `M` (no connection, or `M` cannot be read). */
    UNREACHABLE,

    /** START keeps failing: the pump does not accept state changes (usually a message on the pump screen). */
    NOT_ACCEPTING_COMMANDS
}

/**
 * 4j: when the pump is reported as blocked, and what happens to the boluses waiting in the queue.
 *
 *  - UNREACHABLE: a sent bolus is still UNRESOLVED/UNKNOWN after [UNRESOLVED_BLOCK_MS], or the tracker
 *    already alarmed about it. The driver keeps reading `M`; the block ends when the bolus is resolved.
 *  - NOT_ACCEPTING_COMMANDS: START failed and the retry alarmed (or gave up). Waiting boluses are kept,
 *    not posted and not expired; when a START works their time to live restarts.
 *  - NOT_ACCEPTING_COMMANDS wins: nothing can be delivered until the pump runs again.
 */
object PumpBlockRule {

    const val UNRESOLVED_BLOCK_MS = 5 * BolusTracker.MINUTE

    fun bolusUnresolved(state: BolusState?, sentAt: Long, alarmed: Boolean, now: Long): Boolean =
        (state == BolusState.UNRESOLVED || state == BolusState.UNKNOWN) && (alarmed || now - sentAt > UNRESOLVED_BLOCK_MS)

    fun decide(bolusUnresolved: Boolean, startRefused: Boolean): PumpBlock? = when {
        startRefused    -> PumpBlock.NOT_ACCEPTING_COMMANDS
        bolusUnresolved -> PumpBlock.UNREACHABLE
        else            -> null
    }

    /** Waiting boluses are neither sent nor expired while the pump refuses state changes. */
    fun holdsQueuedBoluses(block: PumpBlock?): Boolean = block == PumpBlock.NOT_ACCEPTING_COMMANDS
}
