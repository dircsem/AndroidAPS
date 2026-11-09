package app.aaps.pump.common.hw.medlink.bolus

/** Why the driver reports the pump as blocked. */
enum class PumpBlock {

    /**
     * START, STOP or bolus commands were confirmed by the MedLink and did nothing (pump still suspended /
     * still running / M shows the bolus not delivered), usually a message on the pump screen.
     */
    PUMP_LOCKED
}

/**
 * When the pump is reported as locked.
 *
 *  - a confirmed command without effect happens now and then on a pump that is not locked, so one is not
 *    enough: the pump is locked after [LOCK_AFTER] in a row ([confirmedWithoutEffect]);
 *  - any command that changes the pump (bolus delivered, suspended, running again) resets the count;
 *  - new commands are not refused while locked: they keep going to the pump (each one with its own tries)
 *    and the first one that works clears the lock.
 */
object PumpLockRule {

    const val LOCK_AFTER = 2

    fun decide(confirmedWithoutEffect: Int): PumpBlock? =
        if (confirmedWithoutEffect >= LOCK_AFTER) PumpBlock.PUMP_LOCKED else null
}
