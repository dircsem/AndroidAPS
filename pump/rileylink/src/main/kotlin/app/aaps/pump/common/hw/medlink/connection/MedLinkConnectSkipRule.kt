package app.aaps.pump.common.hw.medlink.connection

/**
 * When the app may write a command with no OK+CONN before it (the MedLink is still awake).
 *
 * The "connected" flag alone is not enough (field log 2026-11-17 20:55): after an invalid first command the MedLink
 * answers "time to powerdown 20" (not 5), which set the flag, so S / M were written with no connection and failed.
 * User rule (2026-11-17): skip OK+CONN only after a reconnect after a drop (T26), or when the last command was less
 * than [AWAKE_MS] ago. The last CONFIRMED command is used: an invalid one does not keep the MedLink awake.
 */
object MedLinkConnectSkipRule {

    /** The MedLink stays awake this long after a command ("time to powerdown 20"). */
    const val AWAKE_MS = 20_000L

    /**
     * @param lastConfirmedAt last command the MedLink confirmed; 0 = none
     * @param connectedAfterDropAt T26: when the MedLink said the pump is connected after a reconnect after a drop; 0 = none
     */
    fun maySkipOkConn(isConnected: Boolean, now: Long, lastConfirmedAt: Long, connectedAfterDropAt: Long): Boolean {
        if (!isConnected) return false
        if (connectedAfterDropAt > 0L && now - connectedAfterDropAt < AWAKE_MS) return true
        return lastConfirmedAt > 0L && now - lastConfirmedAt < AWAKE_MS
    }
}
