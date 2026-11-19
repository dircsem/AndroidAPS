package app.aaps.pump.common.hw.medlink.connection

/**
 * T26: the Bluetooth link drops after "ok+conn command confirmed for pump wakeup" and before the pump answered
 * ("confirmed pump wakeup" … "ready"). The MedLink goes on waking the pump. Writing OK+CONN again at once gave
 * "invalid command" and lost 10 s (log 2026-11-14 16:35). Rules (user, 2026-11-14):
 * reconnect after [RECONNECT_DELAY_MS], write nothing, and react to the first message ([react]); with no message in
 * [LISTEN_MS], write a new OK+CONN. Only for this connect / wake-up phase; elsewhere the messages keep their meaning.
 */
object MedLinkWakeupDropRule {

    /** Wait before the reconnect (not 5 s: a not accepted OK+CONN powers the MedLink down after about 5 s). */
    const val RECONNECT_DELAY_MS = 2_000L

    /**
     * A wake-up phase older than this is not used for a drop: the pump wake-up takes about 10 s, so a phase with no
     * "ready" after this time is an old one (a missed "ready").
     */
    const val WAKEUP_MAX_MS = 30_000L

    /**
     * Wait after the message that says the pump is connected (GO_ON) before the next command. A command written a few
     * ms after a MedLink message was lost (field log 2026-11-18 01:24:48: `S` 70 ms after it, no answer).
     */
    const val GO_ON_SETTLE_MS = 1_200L

    /** Listen this long after the reconnect (the pump wake-up takes about 10 s). */
    const val LISTEN_MS = 15_000L

    enum class Reaction {
        /** the old wake-up still runs: wait for "ready" */
        WAIT,

        /** the pump is connected: go on with the queued commands */
        GO_ON,

        /** the MedLink is not busy (banner) or did not accept the old OK+CONN (powerdown 5 s): write OK+CONN now */
        SEND_OK_CONN,

        /** "pump no response": the wake-up failed: a new connection, counted as a failed connection (T20) */
        FAILED
    }

    /**
     * The MedLink accepted the OK+CONN and is waking the pump. The answer comes in pieces ("ok+conn command conf",
     * "irmed", "for pump wakeup"): the first piece is enough, so a drop right after it is still seen.
     */
    fun startsWakeup(lastCharacteristic: String, answer: String): Boolean =
        (lastCharacteristic + answer).contains("ok+conn command conf")

    /**
     * A Bluetooth drop is a drop in the wake-up phase when the phase started less than [WAKEUP_MAX_MS] ago. The
     * "connected" flag is not used: an "invalid command" + "time to powerdown 20" before the accepted OK+CONN set it,
     * and 11 of 12 wake-up drops were missed (field log 2026-11-18).
     * @param wakeupSince when "ok+conn command conf" came (0 = not in the wake-up phase)
     */
    fun isWakeupDrop(wakeupSince: Long, now: Long): Boolean = wakeupSince > 0L && now - wakeupSince < WAKEUP_MAX_MS

    private val POWERDOWN = Regex("time to powerdown\\s*(\\d+)(\\s*s)?")

    /**
     * The seconds of "time to powerdown N s"; null until the number is complete. "time to powerdown 20" is exactly one
     * 20-byte chunk, so the "s" comes in the next one: a number of two digits (10, 15, 20) is complete without it. One
     * digit ("1", "2", "5") waits for the "s" or the next digit ("1" + "5s" = 15).
     */
    fun powerdownSeconds(text: String): Int? {
        val match = POWERDOWN.findAll(text).lastOrNull() ?: return null
        val digits = match.groups[1] ?: return null
        val hasS = match.groupValues[2].isNotEmpty()
        // more text after the digits (not an "s"): the number is complete too
        val digitsAtEnd = digits.range.last == text.length - 1
        return if (hasS || digits.value.length >= 2 || !digitsAtEnd) digits.value.toIntOrNull() else null
    }

    /**
     * The general check of "time to powerdown N s" (any time): true = the MedLink is awake (10, 15, 20 s), false = 5 s
     * (the command was not accepted), null = not complete yet or not a powerdown message. The previous chunk is joined,
     * so "time to powerdown 1" + "5s" is 15. (Before: any chunk with a "5" in it counted as 5 s, also "15s".)
     */
    fun powerdownAwake(lastCharacteristic: String, answer: String): Boolean? {
        if (powerdownSeconds(lastCharacteristic) != null) return null // already complete in the previous chunk: counted then
        return powerdownSeconds(lastCharacteristic + answer)?.let { it != 5 }
    }

    /**
     * What to do with a message that came after the reconnect (the last chunk is joined to the new one, because the
     * answer comes in 20-byte pieces). Null = nothing known yet, keep listening.
     */
    fun react(lastCharacteristic: String, answer: String): Reaction? {
        val text = lastCharacteristic + answer
        return when {
            text.contains("pump no response")                    -> Reaction.FAILED
            MedLinkConnectBannerRule.isBannerEnd(lastCharacteristic, answer) -> Reaction.SEND_OK_CONN
            powerdownSeconds(text) == 5                          -> Reaction.SEND_OK_CONN
            powerdownSeconds(text) != null                       -> Reaction.GO_ON
            answer.contains("ready")                             -> Reaction.GO_ON
            text.contains("pump wakeu") || text.contains("medtronic") -> Reaction.WAIT
            else                                                 -> null
        }
    }

    /** How long the OK+CONN still waits while listening; 0 = write it now. */
    fun listenHoldMs(now: Long, listenUntil: Long): Long = if (listenUntil <= 0L) 0L else maxOf(0L, listenUntil - now)
}
