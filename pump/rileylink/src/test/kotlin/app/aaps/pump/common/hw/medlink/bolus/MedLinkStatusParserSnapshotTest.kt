package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkStatusParserSnapshotTest {

    // near the fixture dates (2021): a date far from the read time gets its year normalized (MedLinkPumpClock)
    private val readAt = MedLinkBolusFixtures.pumpTime("10-06-21 17:45")

    @Test
    fun `idle answer parses last bolus, time with minute resolution and zero square bolus`() {
        val s = MedLinkStatusParser.parseBolusSnapshot(MedLinkBolusFixtures.lines("m_idle.txt"), readAt)
        assertNotNull(s)
        s!!
        assertEquals(1.6, s.lastBolusAmount, 0.0001)
        assertEquals(MedLinkBolusFixtures.pumpTime("10-06-21 17:39"), s.lastBolusTime)
        assertEquals(0.0, s.squareBolusAmount!!, 0.0001)
        assertEquals(0.0, s.deliveredAmount!!, 0.0001)
        assertFalse(s.pumpBolusing)
        assertFalse(s.inProgress)
        assertEquals(readAt, s.readAt)
    }

    @Test
    fun `bolusing answer is in progress with delivered so far`() {
        val s = MedLinkStatusParser.parseBolusSnapshot(MedLinkBolusFixtures.lines("m_bolusing.txt"), readAt)!!
        assertEquals(1.0, s.lastBolusAmount, 0.0001)
        assertEquals(1.0, s.squareBolusAmount!!, 0.0001)
        assertEquals(0.4, s.deliveredAmount!!, 0.0001)
        assertTrue(s.inProgress)
    }

    @Test
    fun `missing square bolus line keeps delivered unknown`() {
        val s = MedLinkStatusParser.parseBolusSnapshot(MedLinkBolusFixtures.lines("m_no_square_line.txt"), readAt)!!
        assertEquals(0.3, s.lastBolusAmount, 0.0001)
        assertNull(s.squareBolusAmount)
        assertNull(s.deliveredAmount)
        assertFalse(s.inProgress)
    }

    @Test
    fun `missing last bolus line returns null, never a default of zero`() {
        assertNull(MedLinkStatusParser.parseBolusSnapshot(MedLinkBolusFixtures.lines("m_missing_last_bolus.txt"), readAt))
    }

    @Test
    fun `last bolus without a time returns null`() {
        assertNull(MedLinkStatusParser.parseBolusSnapshot(listOf("last bolus: 0.5u", "square bolus: 0.0u delivered: 0.000u"), readAt))
    }

    @Test
    fun `explicit bolusing line marks the pump as bolusing`() {
        val s = MedLinkStatusParser.parseBolusSnapshot(MedLinkBolusFixtures.lines("m_idle.txt") + "pump bolusing state", readAt)!!
        assertTrue(s.pumpBolusing)
        assertTrue(s.inProgress)
    }

    @Test
    fun `lines joined without newlines (BLE chunks) are parsed by their labels`() {
        val joined = listOf("last bolus: 0.5u 10-06-21 17:45square bolus: 0.0u delivered: 0.000uready")
        val s = MedLinkStatusParser.parseBolusSnapshot(joined, readAt)!!
        assertEquals(0.5, s.lastBolusAmount, 0.0001)
        assertEquals(0.0, s.squareBolusAmount!!, 0.0001)
        assertEquals(0.0, s.deliveredAmount!!, 0.0001)
        assertEquals(MedLinkBolusFixtures.pumpTime("10-06-21 17:45"), s.lastBolusTime)
    }

    @Test
    fun `lines split across BLE chunks are parsed`() {
        val chunks = listOf("last bolus: 1.", "0u 10-06-21 17:45\nsqua", "re bolus: 1.0u deliv", "ered: 0.400u\n")
        val s = MedLinkStatusParser.parseBolusSnapshot(chunks, readAt)!!
        assertEquals(1.0, s.lastBolusAmount, 0.0001)
        assertEquals(0.4, s.deliveredAmount!!, 0.0001)
        assertTrue(s.inProgress)
    }

    @Test
    fun `raw answer is kept for logging`() {
        val s = MedLinkStatusParser.parseBolusSnapshot(MedLinkBolusFixtures.lines("m_idle.txt"), readAt)!!
        assertTrue(s.raw.contains("last bolus: 1.6u"))
    }
}
