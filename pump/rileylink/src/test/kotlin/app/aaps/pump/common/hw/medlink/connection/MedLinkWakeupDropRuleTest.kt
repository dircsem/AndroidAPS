package app.aaps.pump.common.hw.medlink.connection

import app.aaps.pump.common.hw.medlink.connection.MedLinkWakeupDropRule.Reaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T26 (log 2026-11-14 16:35): the Bluetooth link dropped after "ok+conn command confirmed for pump wakeup". The app
 * reconnected at once and wrote OK+CONN again: "invalid command", 10 s lost, while the MedLink was still waking the
 * pump. Now: reconnect after 2 s, write nothing, react to the first message.
 */
class MedLinkWakeupDropRuleTest {

    @Test
    fun `wake-up phase - from the confirmed OK+CONN to the pump answer`() {
        assertTrue(MedLinkWakeupDropRule.startsWakeup("", "ok+conn command conf"))
        assertTrue(MedLinkWakeupDropRule.startsWakeup("ok+conn command conf", "irmed"))
        assertFalse(MedLinkWakeupDropRule.startsWakeup("", "e ok+conn or command"))
    }

    @Test
    fun `after the reconnect - the banner - send OK+CONN at once`() {
        assertEquals(Reaction.SEND_OK_CONN, MedLinkWakeupDropRule.react("0%", "e ok+conn or command"))
    }

    @Test
    fun `after the reconnect - wake-up messages - wait, no OK+CONN`() {
        assertEquals(Reaction.WAIT, MedLinkWakeupDropRule.react("", "for pump wakeup"))
        assertEquals(Reaction.WAIT, MedLinkWakeupDropRule.react("", "confirmed pump wakeu"))
        assertEquals(Reaction.WAIT, MedLinkWakeupDropRule.react("", "medtronic veo"))
    }

    @Test
    fun `after the reconnect - ready - go on with the queue`() {
        assertEquals(Reaction.GO_ON, MedLinkWakeupDropRule.react("medtronic veo", "\nready"))
    }

    @Test
    fun `time to powerdown 5s - not accepted - send OK+CONN, not a failure`() {
        assertEquals(Reaction.SEND_OK_CONN, MedLinkWakeupDropRule.react("", "time to powerdown 5s"))
        assertEquals(Reaction.SEND_OK_CONN, MedLinkWakeupDropRule.react("time to powerdown 5", "s"))
    }

    @Test
    fun `time to powerdown 10, 15, 20 s - the pump is connected - go on`() {
        for (n in listOf(10, 15, 20)) assertEquals(Reaction.GO_ON, MedLinkWakeupDropRule.react("", "time to powerdown ${n}s"), "$n")
    }

    @Test
    fun `the number split over two chunks - 15, not 5`() {
        assertEquals(Reaction.GO_ON, MedLinkWakeupDropRule.react("time to powerdown 1", "5s"))
        assertEquals(15, MedLinkWakeupDropRule.powerdownSeconds("time to powerdown 15s"))
    }

    @Test
    fun `a number without its s is not read yet`() {
        assertNull(MedLinkWakeupDropRule.powerdownSeconds("time to powerdown 5"))
        assertNull(MedLinkWakeupDropRule.react("", "time to powerdown 1"))
    }

    @Test
    fun `pump no response - new connection, counted`() {
        assertEquals(Reaction.FAILED, MedLinkWakeupDropRule.react("", "pump no response"))
    }

    @Test
    fun `another text - keep listening`() {
        assertNull(MedLinkWakeupDropRule.react("", "med-link wake up by"))
    }

    @Test
    fun `listen time - hold the OK+CONN until the end, then write it`() {
        val until = 1_000_000L
        assertEquals(5_000L, MedLinkWakeupDropRule.listenHoldMs(now = until - 5_000L, listenUntil = until))
        assertEquals(0L, MedLinkWakeupDropRule.listenHoldMs(now = until, listenUntil = until))
        assertEquals(0L, MedLinkWakeupDropRule.listenHoldMs(now = until, listenUntil = 0L))
    }

    // the general check (any time, not only after a drop): "connected" only for 10 / 15 / 20 s, never for 5 s

    @Test
    fun `powerdown 20 is one 20-byte chunk - read at once, the s comes later`() {
        assertEquals(20, MedLinkWakeupDropRule.powerdownSeconds("time to powerdown 20"))
        assertEquals(true, MedLinkWakeupDropRule.powerdownAwake("", "time to powerdown 20"))
        // and the next chunk "s" does not count it again
        assertNull(MedLinkWakeupDropRule.powerdownAwake("time to powerdown 20", "s"))
    }

    @Test
    fun `powerdown awake - 10, 15 and 20 s yes, 5 s no`() {
        assertEquals(true, MedLinkWakeupDropRule.powerdownAwake("", "time to powerdown 20s"))
        assertEquals(true, MedLinkWakeupDropRule.powerdownAwake("", "time to powerdown 10s"))
        assertEquals(false, MedLinkWakeupDropRule.powerdownAwake("", "time to powerdown 5s"))
    }

    @Test
    fun `powerdown awake - 15 split as 1 and 5s is 15, not 5`() {
        assertEquals(true, MedLinkWakeupDropRule.powerdownAwake("time to powerdown 1", "5s"))
        assertEquals(false, MedLinkWakeupDropRule.powerdownAwake("time to powerdown 5", "s"))
    }

    @Test
    fun `powerdown awake - not complete or not a powerdown - no answer yet`() {
        assertNull(MedLinkWakeupDropRule.powerdownAwake("", "time to powerdown 2"))
        assertNull(MedLinkWakeupDropRule.powerdownAwake("", "time to powerdown 5"))
        assertNull(MedLinkWakeupDropRule.powerdownAwake("", "pump normal state"))
        // the next chunk after a complete message does not count it again
        assertNull(MedLinkWakeupDropRule.powerdownAwake("time to powerdown 20s", "\n"))
    }

    /**
     * Field log 2026-11-18: 12 drops in the wake-up phase, only 1 handled. An "invalid command" + "time to powerdown 20"
     * before the accepted OK+CONN had set "connected", and the drop check needed "not connected". Now only the phase
     * counts (the "connected" flag is not used).
     */
    @Test
    fun `drop in the wake-up phase - only the phase counts`() {
        val confirmed = 1_000_000L
        // 01:54:00 "ok+conn command conf" -> 01:54:06 drop
        assertTrue(MedLinkWakeupDropRule.isWakeupDrop(wakeupSince = confirmed, now = confirmed + 6_000))
        assertFalse(MedLinkWakeupDropRule.isWakeupDrop(wakeupSince = 0L, now = confirmed))
    }

    @Test
    fun `drop long after the wake-up started - not a wake-up drop (no ready seen, old phase)`() {
        val confirmed = 1_000_000L
        assertTrue(MedLinkWakeupDropRule.isWakeupDrop(confirmed, confirmed + MedLinkWakeupDropRule.WAKEUP_MAX_MS - 1))
        assertFalse(MedLinkWakeupDropRule.isWakeupDrop(confirmed, confirmed + MedLinkWakeupDropRule.WAKEUP_MAX_MS))
    }
}
