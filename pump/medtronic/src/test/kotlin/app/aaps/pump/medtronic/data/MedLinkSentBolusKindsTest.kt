package app.aaps.pump.medtronic.data

import app.aaps.core.data.model.BS
import app.aaps.pump.medtronic.data.MedLinkSentBolusKinds.Sent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Field log 2026-11-18 10:50: an SMB of 0.3 U was sent at 10:50:19; the pump history read stored it at 10:50:59 as a
 * NORMAL (manual) bolus with pump time 10:50:00, before the bolus tracker stored it as SMB (that insert was then a
 * duplicate). Two TBR boluses were then skipped: "last TBR or manual bolus 60 s ago".
 */
class MedLinkSentBolusKindsTest {

    private val min = 60_000L
    private val sentAt = 1_795_009_819_000L          // 10:50:19
    private val pumpTime = 1_795_009_800_000L        // 10:50:00 (pump history, minute resolution)

    @Test
    fun `the SMB of the log - the history bolus gets the SMB type`() {
        val sent = listOf(Sent(BS.Type.SMB, 0.3, sentAt))
        assertEquals(BS.Type.SMB, MedLinkSentBolusKinds.match(0.3, pumpTime, sent)?.type)
    }

    @Test
    fun `another amount - no match, stays as it is`() {
        val sent = listOf(Sent(BS.Type.SMB, 0.3, sentAt))
        assertNull(MedLinkSentBolusKinds.match(0.2, pumpTime, sent))
    }

    @Test
    fun `history time far from the send time - no match`() {
        val sent = listOf(Sent(BS.Type.SMB, 0.3, sentAt))
        assertNull(MedLinkSentBolusKinds.match(0.3, sentAt - 3 * min, sent))
        assertNull(MedLinkSentBolusKinds.match(0.3, sentAt + 16 * min, sent))
    }

    @Test
    fun `the time window - 2 min before the send, 15 min after`() {
        val sent = listOf(Sent(BS.Type.TBR, 0.1, sentAt))
        assertEquals(BS.Type.TBR, MedLinkSentBolusKinds.match(0.1, sentAt - 2 * min, sent)?.type)
        assertEquals(BS.Type.TBR, MedLinkSentBolusKinds.match(0.1, sentAt + 15 * min, sent)?.type)
    }

    @Test
    fun `same amount sent twice - the closest send time wins`() {
        val manual = Sent(BS.Type.NORMAL, 0.3, sentAt)
        val smb = Sent(BS.Type.SMB, 0.3, sentAt + 6 * min)
        assertEquals(BS.Type.NORMAL, MedLinkSentBolusKinds.match(0.3, pumpTime, listOf(smb, manual))?.type)
        assertEquals(BS.Type.SMB, MedLinkSentBolusKinds.match(0.3, sentAt + 6 * min, listOf(manual, smb))?.type)
    }

    @Test
    fun `TBR boluses every 5 min - each history bolus gets its own send`() {
        val sent = (0 until 3).map { Sent(BS.Type.TBR, 0.1, sentAt + it * 5 * min) }
        for (i in 0 until 3) {
            assertEquals(sent[i], MedLinkSentBolusKinds.match(0.1, sentAt + i * 5 * min - 19_000L, sent))
        }
    }

    @Test
    fun `old sends are removed after the keep time`() {
        val old = Sent(BS.Type.SMB, 0.3, sentAt)
        val fresh = Sent(BS.Type.TBR, 0.1, sentAt + 20 * min)
        assertEquals(listOf(fresh), MedLinkSentBolusKinds.prune(listOf(old, fresh), now = sentAt + MedLinkSentBolusKinds.KEEP_MS))
    }

    /**
     * An SMB and a TBR bolus joined: the pump history has one bolus with the sum. The tracker records the parts at pump
     * time + i s; the history read must store the same parts, else the TBR part counts twice.
     */
    @Test
    fun `joined SMB and TBR bolus - the history bolus is split into the parts`() {
        val smb = Sent(BS.Type.SMB, 0.3, sentAt)
        val tbr = Sent(BS.Type.TBR, 0.1, sentAt + 400)
        val parts = MedLinkSentBolusKinds.matchJoined(0.4, pumpTime, listOf(tbr, smb))
        assertEquals(listOf(smb, tbr), parts)
    }

    @Test
    fun `two sends far apart - not a joined bolus`() {
        val smb = Sent(BS.Type.SMB, 0.3, sentAt)
        val tbr = Sent(BS.Type.TBR, 0.1, sentAt + 3 * min)
        assertNull(MedLinkSentBolusKinds.matchJoined(0.4, pumpTime, listOf(smb, tbr)))
    }

    @Test
    fun `sum does not match (part delivered) - not split`() {
        val smb = Sent(BS.Type.SMB, 0.3, sentAt)
        val tbr = Sent(BS.Type.TBR, 0.1, sentAt + 400)
        assertNull(MedLinkSentBolusKinds.matchJoined(0.35, pumpTime, listOf(smb, tbr)))
    }

    @Test
    fun `one send alone - not a joined bolus`() {
        assertNull(MedLinkSentBolusKinds.matchJoined(0.3, pumpTime, listOf(Sent(BS.Type.SMB, 0.3, sentAt))))
    }
}
