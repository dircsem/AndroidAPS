package app.aaps.pump.common.hw.medlink.connection

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Field log 2026-11-17 20:55 (`logs/AndroidAPS.log.log`): after an invalid first command the MedLink answers
 * "time to powerdown 20" (not 5), so the app took the link as connected, skipped OK+CONN and wrote S / M with no
 * connection: every command failed. User rule (2026-11-17): skip OK+CONN only after a reconnect after a drop, or when
 * the last command was less than 20 s ago (here: the last confirmed command, an invalid one does not count).
 */
class MedLinkConnectSkipRuleTest {

    private val now = 1_000_000L

    @Test
    fun `field case - connected flag set by powerdown 20 after an invalid OK+CONN, no confirmed command - send OK+CONN`() {
        assertFalse(MedLinkConnectSkipRule.maySkipOkConn(isConnected = true, now = now, lastConfirmedAt = now - 60_000L, connectedAfterDropAt = 0L))
        assertFalse(MedLinkConnectSkipRule.maySkipOkConn(isConnected = true, now = now, lastConfirmedAt = 0L, connectedAfterDropAt = 0L))
    }

    @Test
    fun `a command confirmed less than 20 s ago - the MedLink is still awake - skip OK+CONN`() {
        assertTrue(MedLinkConnectSkipRule.maySkipOkConn(isConnected = true, now = now, lastConfirmedAt = now - 19_000L, connectedAfterDropAt = 0L))
    }

    @Test
    fun `20 s or more - send OK+CONN`() {
        assertFalse(MedLinkConnectSkipRule.maySkipOkConn(isConnected = true, now = now, lastConfirmedAt = now - 20_000L, connectedAfterDropAt = 0L))
    }

    @Test
    fun `just reconnected after a drop and the MedLink said the pump is connected - skip OK+CONN (T26)`() {
        assertTrue(MedLinkConnectSkipRule.maySkipOkConn(isConnected = true, now = now, lastConfirmedAt = 0L, connectedAfterDropAt = now - 2_000L))
        assertFalse(MedLinkConnectSkipRule.maySkipOkConn(isConnected = true, now = now, lastConfirmedAt = 0L, connectedAfterDropAt = now - 25_000L))
    }

    @Test
    fun `not connected - always OK+CONN`() {
        assertFalse(MedLinkConnectSkipRule.maySkipOkConn(isConnected = false, now = now, lastConfirmedAt = now - 1_000L, connectedAfterDropAt = now - 1_000L))
    }
}
