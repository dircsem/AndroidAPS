package app.aaps.pump.medtronic.data

import app.aaps.core.data.model.BS
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The pump history does not know the type of a bolus: every history bolus comes as NORMAL (manual). When the history
 * read stores a bolus before the bolus tracker does, the tracker's SMB / TBR record is a duplicate and the bolus
 * stays "manual" (field log 2026-11-18 10:50: an SMB of 0.3 U; two TBR boluses were then skipped as "too soon after a
 * manual bolus").
 *
 * So the app keeps the boluses it sent (type, amount, time), and a history bolus with the same amount close to the
 * send time gets that type. A history bolus with no match stays as it is (NORMAL).
 */
object MedLinkSentBolusKinds {

    /** The pump history time is rounded down to the minute, and the pump clock can be a little off. */
    const val BEFORE_MS = 2 * 60_000L

    /** The history can be read some minutes after the bolus (slow link, retries). */
    const val AFTER_MS = 15 * 60_000L

    /** Sends older than this are removed. */
    const val KEEP_MS = 30 * 60_000L

    /** Amounts are equal when they differ less than this (the pump steps are 0.1 U / 0.05 U / 0.025 U). */
    private const val AMOUNT_TOLERANCE = 0.001

    data class Sent(val type: BS.Type, val amount: Double, val sentAt: Long)

    /** The send that matches a history bolus: same amount, time in the window; the closest send time wins. */
    fun match(amount: Double, historyTime: Long, sent: List<Sent>): Sent? =
        sent.filter {
            abs(it.amount - amount) < AMOUNT_TOLERANCE &&
                historyTime >= it.sentAt - BEFORE_MS && historyTime <= it.sentAt + AFTER_MS
        }.minByOrNull { abs(historyTime - it.sentAt) }

    /** Sends this close to the first one can be one pump bolus (the second joined the first before it was written). */
    const val JOIN_MS = 2 * 60_000L

    /**
     * A history bolus that is the sum of 2 or 3 sends of different types close together: the bolus tracker joined them
     * into one pump bolus and records them as parts (part i at pump time + i s). The history read must store the same
     * parts, or the parts after the first one count twice. Returns the parts in send order (the queue order), or null.
     * Only when the sum is equal: a part delivered bolus is not split (it can not be told apart from a single bolus).
     */
    fun matchJoined(amount: Double, historyTime: Long, sent: List<Sent>): List<Sent>? {
        val ordered = sent.sortedBy { it.sentAt }
        for (size in 3 downTo 2) {
            for (start in 0..ordered.size - size) {
                val group = ordered.subList(start, start + size)
                val first = group.first()
                if (group.last().sentAt - first.sentAt > JOIN_MS) continue
                if (group.map { it.type }.toSet().size != size) continue
                if (historyTime < first.sentAt - BEFORE_MS || historyTime > first.sentAt + AFTER_MS) continue
                val sum = (group.sumOf { it.amount } * 100.0).roundToLong() / 100.0
                if (abs(sum - amount) < AMOUNT_TOLERANCE) return group.toList()
            }
        }
        return null
    }

    fun prune(sent: List<Sent>, now: Long): List<Sent> = sent.filter { now - it.sentAt < KEEP_MS }
}
