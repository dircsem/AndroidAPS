package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.bolus.BolusOutcome.Type
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BolusStatusInterpreterTest {

    private val min = 60_000L

    // baseline: previous bolus 0.3 U ten minutes before sending
    private val sentAt = Fixtures.pumpTime("10-06-21 17:45") + 10_000
    private val baseline = Fixtures.snapshot(amount = 0.3, time = Fixtures.pumpTime("10-06-21 17:35"), readAt = sentAt - 5_000)
    private val newTime = Fixtures.pumpTime("10-06-21 17:45")

    private fun check(requested: Double, base: BolusStatusSnapshot? = baseline) =
        BolusCheck(requested = requested, sentAt = sentAt, baseline = base, expectedEndAt = sentAt + (requested * 40_000).toLong() + 15_000)

    private val afterEnd = sentAt + 10 * min

    @Test
    fun `null snapshot is unknown`() {
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(null, check(0.3), afterEnd).type)
    }

    @Test
    fun `same dose with the baseline time is not started (root cause 4)`() {
        val s = Fixtures.snapshot(amount = 0.3, time = baseline.lastBolusTime)
        assertEquals(Type.NOT_STARTED, BolusStatusInterpreter.interpret(s, check(0.3), afterEnd).type)
    }

    @Test
    fun `older than baseline is unknown, not not-started`() {
        val s = Fixtures.snapshot(amount = 0.3, time = baseline.lastBolusTime - 5 * min)
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(s, check(0.3), afterEnd).type)
    }

    @Test
    fun `baseline bolus in the same minute as the send is ambiguous - unknown, never not-started`() {
        val base = Fixtures.snapshot(amount = 0.3, time = newTime, readAt = sentAt - 2_000)
        val s = Fixtures.snapshot(amount = 0.3, time = newTime)
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(s, check(0.3, base), afterEnd).type)
    }

    @Test
    fun `newer and in progress is delivering`() {
        val s = Fixtures.snapshot(amount = 1.0, time = newTime, square = 1.0, delivered = 0.4)
        val o = BolusStatusInterpreter.interpret(s, check(1.0), sentAt + 20_000)
        assertEquals(Type.DELIVERING, o.type)
        assertEquals(0.4, o.deliveredAmount, 0.0001)
    }

    @Test
    fun `pump bolusing flag is delivering`() {
        val s = Fixtures.snapshot(amount = 1.0, time = newTime, bolusing = true)
        assertEquals(Type.DELIVERING, BolusStatusInterpreter.interpret(s, check(1.0), sentAt + 20_000).type)
    }

    @Test
    fun `newer and equal to requested within tolerance is delivered`() {
        val s = Fixtures.snapshot(amount = 0.95, time = newTime)
        val o = BolusStatusInterpreter.interpret(s, check(1.0), afterEnd)
        assertEquals(Type.DELIVERED, o.type)
        assertEquals(0.95, o.deliveredAmount, 0.0001)
        assertEquals(newTime, o.pumpBolusTime)
    }

    @Test
    fun `newer, less than requested and not bolusing after expected end is partial`() {
        val s = Fixtures.snapshot(amount = 0.4, time = newTime)
        val o = BolusStatusInterpreter.interpret(s, check(1.0), afterEnd)
        assertEquals(Type.PARTIAL, o.type)
        assertEquals(0.4, o.deliveredAmount, 0.0001)
    }

    @Test
    fun `newer, less than requested before expected end is still delivering`() {
        val s = Fixtures.snapshot(amount = 0.4, time = newTime)
        assertEquals(Type.DELIVERING, BolusStatusInterpreter.interpret(s, check(1.0), sentAt + 10_000).type)
    }

    @Test
    fun `newer bolus larger than requested is unknown (someone bolused on the pump)`() {
        val s = Fixtures.snapshot(amount = 2.0, time = newTime)
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(s, check(1.0), afterEnd).type)
    }

    @Test
    fun `in progress bolus larger than requested is unknown`() {
        val s = Fixtures.snapshot(amount = 2.0, time = newTime, square = 2.0, delivered = 0.5)
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(s, check(1.0), sentAt + 20_000).type)
    }

    @Test
    fun `no baseline - clearly older bolus is not started`() {
        val s = Fixtures.snapshot(amount = 0.3, time = sentAt - 30 * min)
        assertEquals(Type.NOT_STARTED, BolusStatusInterpreter.interpret(s, check(0.3, base = null), afterEnd).type)
    }

    @Test
    fun `no baseline - bolus around send time is judged as ours`() {
        val s = Fixtures.snapshot(amount = 0.3, time = newTime)
        assertEquals(Type.DELIVERED, BolusStatusInterpreter.interpret(s, check(0.3, base = null), afterEnd).type)
    }

    @Test
    fun `no baseline - bolus one to two minutes before send is ambiguous`() {
        val s = Fixtures.snapshot(amount = 0.3, time = sentAt - 90_000)
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(s, check(0.3, base = null), afterEnd).type)
    }

    @Test
    fun `pump clock offset is applied when there is no baseline`() {
        // pump clock is 30 min behind the phone: a bolus stamped 30 min before send is ours
        val s = Fixtures.snapshot(amount = 0.3, time = newTime - 30 * min)
        val c = check(0.3, base = null).copy(pumpClockOffsetMs = -30 * min)
        assertEquals(Type.DELIVERED, BolusStatusInterpreter.interpret(s, c, afterEnd).type)
    }

    @Test
    fun `two equal SMBs back to back are told apart by the baseline time`() {
        // first SMB at 17:45 is the baseline for the second one
        val first = Fixtures.snapshot(amount = 0.3, time = newTime, readAt = newTime + 2 * min)
        val secondSent = newTime + 3 * min
        val c = BolusCheck(0.3, secondSent, first, secondSent + 30_000)
        assertEquals(Type.NOT_STARTED, BolusStatusInterpreter.interpret(first, c, secondSent + 5 * min).type)
        val second = Fixtures.snapshot(amount = 0.3, time = newTime + 3 * min)
        assertEquals(Type.DELIVERED, BolusStatusInterpreter.interpret(second, c, secondSent + 5 * min).type)
    }

    @Test
    fun `real fixture - aborted bolus gives partial`() {
        val s = MedLinkStatusParser.parseBolusSnapshot(Fixtures.lines("m_partial_aborted.txt"), afterEnd)
        val o = BolusStatusInterpreter.interpret(s, check(1.0), afterEnd)
        assertEquals(Type.PARTIAL, o.type)
        assertEquals(0.4, o.deliveredAmount, 0.0001)
    }

    // 4o (logs/bolus_error_message.log): a normal 0.7 U bolus while it runs is on the square line, the last bolus is the old one
    private fun m(text: String, readAt: Long) = MedLinkStatusParser.parseBolusSnapshot(text.lines(), readAt)!!

    private val logBaseline = m("last bolus: 1.5u 10-06-21 17:35\nsquare bolus: 0.0u delivered: 0.000u\nsquare bolus time: 0h:00m / 0h:00m", sentAt - 5_000)
    private val logRunning = "last bolus: 1.5u 10-06-21 17:35\nsquare bolus: 0.7u delivered: 0.200u\nsquare bolus time: 0h:00m / 0h:00m"

    @Test
    fun `sent normal bolus running on the square line is delivering, not not-started`() {
        val o = BolusStatusInterpreter.interpret(m(logRunning, sentAt + 26_000), check(0.7, logBaseline), sentAt + 26_000)
        assertEquals(Type.DELIVERING, o.type)
        assertEquals(0.2, o.deliveredAmount, 0.0001)
    }

    @Test
    fun `square line read before the send is not the sent bolus`() {
        val s = m(logRunning, sentAt - 1_000)
        assertEquals(false, BolusStatusInterpreter.isSentBolusOnSquareLine(s, check(0.7, logBaseline)))
    }

    @Test
    fun `sent bolus done on the square line but last bolus not updated is unknown, never not-started`() {
        val s = m("last bolus: 1.5u 10-06-21 17:35\nsquare bolus: 0.7u delivered: 0.700u\nsquare bolus time: 0h:00m / 0h:00m", sentAt + 40_000)
        assertEquals(Type.UNKNOWN, BolusStatusInterpreter.interpret(s, check(0.7, logBaseline), sentAt + 40_000).type)
    }

    @Test
    fun `square line bigger than requested is another bolus (the tracker waits for it)`() {
        val s = m("last bolus: 1.5u 10-06-21 17:35\nsquare bolus: 2.0u delivered: 0.200u\nsquare bolus time: 0h:00m / 0h:00m", sentAt + 20_000)
        assertEquals(Type.NOT_STARTED, BolusStatusInterpreter.interpret(s, check(0.7, logBaseline), sentAt + 20_000).type)
    }

    @Test
    fun `a real square wave (duration above 0) is not the sent normal bolus`() {
        val s = m("last bolus: 1.5u 10-06-21 17:35\nsquare bolus: 0.7u delivered: 0.200u\nsquare bolus time: 0h:10m / 0h:30m", sentAt + 20_000)
        assertEquals(30, s.squareMinutes)
        assertEquals(Type.NOT_STARTED, BolusStatusInterpreter.interpret(s, check(0.7, logBaseline), sentAt + 20_000).type)
    }
}
