package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.bolus.BolusAction.Alarm
import app.aaps.pump.common.hw.medlink.bolus.BolusAction.AskUser
import app.aaps.pump.common.hw.medlink.bolus.BolusAction.Fail
import app.aaps.pump.common.hw.medlink.bolus.BolusAction.None
import app.aaps.pump.common.hw.medlink.bolus.BolusAction.ReadStatusAt
import app.aaps.pump.common.hw.medlink.bolus.BolusAction.Record
import app.aaps.pump.common.hw.medlink.bolus.BolusAction.Resend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BolusTrackerTest {

    private class FakeClock(var t: Long) : Clock {

        override fun now() = t
        fun advance(ms: Long) {
            t += ms
        }
    }

    private val min = 60_000L
    private val t0 = Fixtures.pumpTime("10-06-21 17:45") + 10_000
    private lateinit var clock: FakeClock
    private lateinit var log: MutableList<String>
    private lateinit var tracker: BolusTracker

    private val baseline = Fixtures.snapshot(amount = 0.3, time = Fixtures.pumpTime("10-06-21 17:30"), readAt = t0 - 2_000)
    private fun applied(amount: Double, minuteOffset: Int = 0, readAt: Long = clock.now()) =
        Fixtures.snapshot(amount = amount, time = Fixtures.pumpTime("10-06-21 17:45") + minuteOffset * min, readAt = readAt)

    private fun notStarted() = baseline.copy(readAt = clock.now())

    @BeforeEach
    fun setup() {
        clock = FakeClock(t0)
        log = mutableListOf()
        tracker = BolusTracker(clock, strict = true) { log.add(it) }
    }

    private fun sendLoopBolus(amount: Double = 0.5, origin: BolusOrigin = BolusOrigin.LOOP): BolusRequest {
        val r = tracker.onQueued("b1", origin, amount)!!
        tracker.onBaseline(baseline)
        tracker.onWritten()
        return r
    }

    // ---- T9 state machine -------------------------------------------------------------------

    @Test
    fun `happy path moves queued-sent-confirmed-delivered and records once`() {
        val r = sendLoopBolus()
        assertEquals(BolusState.SENT, r.state)
        val verify = tracker.onConfirmed()
        assertEquals(BolusState.CONFIRMED, r.state)
        assertInstanceOf(ReadStatusAt::class.java, verify)
        assertTrue((verify as ReadStatusAt).time >= t0 + (0.5 * 40_000).toLong())

        clock.advance(2 * min)
        val action = tracker.onStatus(applied(0.5))
        assertEquals(Record(0.5, applied(0.5).lastBolusTime), action)
        assertEquals(BolusState.DELIVERED, r.state)
        assertTrue(tracker.canSendNewBolus())
    }

    @Test
    fun `confirmation alone never finishes a bolus (rule 1)`() {
        sendLoopBolus()
        tracker.onConfirmed()
        assertFalse(tracker.canSendNewBolus())
        assertTrue(tracker.hasUnresolved())
    }

    @Test
    fun `only one bolus in flight`() {
        sendLoopBolus()
        assertEquals(null, tracker.onQueued("b2", BolusOrigin.LOOP, 0.3))
    }

    @Test
    fun `illegal transition throws in strict mode`() {
        tracker.onQueued("b1", BolusOrigin.LOOP, 0.5)
        assertThrows<IllegalStateException> { tracker.onConfirmed() }
    }

    @Test
    fun `illegal transition alarms in release mode`() {
        val relaxed = BolusTracker(clock, strict = false) { log.add(it) }
        relaxed.onQueued("b1", BolusOrigin.LOOP, 0.5)
        assertInstanceOf(Alarm::class.java, relaxed.onConfirmed())
    }

    @Test
    fun `every transition is logged with the bolus id (T12)`() {
        sendLoopBolus()
        tracker.onConfirmed()
        assertTrue(log.any { it.startsWith("BOLUS[b1]") && it.contains("SENT->CONFIRMED") })
        assertTrue(log.any { it.contains("requested=0.5") })
    }

    // ---- T7 re-send rules -------------------------------------------------------------------

    @Test
    fun `confirmed and M shows the bolus - no resend`() {
        sendLoopBolus()
        tracker.onConfirmed()
        clock.advance(min)
        assertInstanceOf(Record::class.java, tracker.onStatus(applied(0.5)))
        assertEquals(0, tracker.resendCount())
    }

    @Test
    fun `confirmed but M shows no new bolus - fresh M, then exactly one resend, then recorded once`() {
        val r = sendLoopBolus()
        tracker.onConfirmed()
        clock.advance(min)
        val first = tracker.onStatus(notStarted())
        assertInstanceOf(ReadStatusAt::class.java, first, "first not-started must ask for a fresh M")
        assertEquals(BolusState.UNRESOLVED, r.state)

        clock.advance(5_000)
        assertEquals(Resend, tracker.onStatus(notStarted()))

        tracker.onWritten()
        tracker.onConfirmed()
        clock.advance(min)
        assertInstanceOf(Record::class.java, tracker.onStatus(applied(0.5, minuteOffset = 1)))
        assertEquals(1, tracker.resendCount())
        // second sighting of the same pump bolus does not record again
        assertEquals(None, tracker.onStatus(applied(0.5, minuteOffset = 1)))
    }

    @Test
    fun `after max resends still not started - alarm, no further resend`() {
        sendLoopBolus()
        tracker.onConfirmed()
        tracker.onStatus(notStarted())
        assertEquals(Resend, tracker.onStatus(notStarted()))
        tracker.onWritten()
        clock.advance(min)
        tracker.onStatus(notStarted())
        val action = tracker.onStatus(notStarted())
        assertInstanceOf(Alarm::class.java, action)
        assertEquals(BolusState.FAILED, tracker.current!!.state)
    }

    @Test
    fun `disconnect after write, after confirm and mid delivery never resends directly`() {
        sendLoopBolus()
        assertInstanceOf(ReadStatusAt::class.java, tracker.onDisconnect())
        setup()
        sendLoopBolus(); tracker.onConfirmed()
        assertInstanceOf(ReadStatusAt::class.java, tracker.onDisconnect())
        setup()
        sendLoopBolus(); tracker.onConfirmed()
        tracker.onStatus(Fixtures.snapshot(1.0, applied(1.0).lastBolusTime, square = 0.5, delivered = 0.2))
        assertEquals(BolusState.DELIVERING, tracker.current!!.state)
        assertInstanceOf(ReadStatusAt::class.java, tracker.onDisconnect())
        assertEquals(BolusState.UNRESOLVED, tracker.current!!.state)
    }

    @Test
    fun `disconnect before the bolus was written does nothing`() {
        tracker.onQueued("b1", BolusOrigin.LOOP, 0.5)
        assertEquals(None, tracker.onDisconnect())
    }

    @Test
    fun `stale loop bolus is failed, not resent`() {
        sendLoopBolus()
        tracker.onConfirmed()
        clock.advance(6 * min)
        tracker.onStatus(notStarted())
        val action = tracker.onStatus(notStarted())
        assertInstanceOf(Fail::class.java, action)
        assertEquals(BolusState.FAILED, tracker.current!!.state)
        assertTrue(tracker.canSendNewBolus())
    }

    @Test
    fun `manual bolus not started asks the user`() {
        sendLoopBolus(origin = BolusOrigin.MANUAL)
        tracker.onConfirmed()
        tracker.onStatus(notStarted())
        assertInstanceOf(AskUser::class.java, tracker.onStatus(notStarted()))
        assertFalse(tracker.canSendNewBolus())
        assertEquals(Resend, tracker.onUserResendAnswer(true))
    }

    @Test
    fun `manual bolus declined by the user fails`() {
        sendLoopBolus(origin = BolusOrigin.MANUAL)
        tracker.onStatus(notStarted())
        tracker.onStatus(notStarted())
        assertInstanceOf(Fail::class.java, tracker.onUserResendAnswer(false))
        assertTrue(tracker.canSendNewBolus())
    }

    @Test
    fun `partial is recorded without remainder resend`() {
        sendLoopBolus()
        tracker.onConfirmed()
        clock.advance(5 * min)
        assertEquals(Record(0.3, applied(0.3).lastBolusTime), tracker.onStatus(applied(0.3)))
        assertEquals(BolusState.PARTIAL, tracker.current!!.state)
        assertEquals(0, tracker.resendCount())
        assertTrue(tracker.current!!.appliedTotal <= tracker.current!!.requested)
    }

    @Test
    fun `delivering schedules the next M for the remaining dose`() {
        sendLoopBolus(amount = 1.0)
        tracker.onConfirmed()
        clock.advance(20_000)
        val action = tracker.onStatus(Fixtures.snapshot(1.0, applied(1.0).lastBolusTime, square = 1.0, delivered = 0.6, readAt = clock.now()))
        assertEquals(ReadStatusAt(clock.now() + 16_000 + 15_000), action)
    }

    @Test
    fun `two 0_3 U SMBs back to back give two records with different pump times`() {
        sendLoopBolus(0.3)
        clock.advance(min)
        val firstRecord = tracker.onStatus(applied(0.3)) as Record
        val firstSnapshot = applied(0.3)

        clock.advance(2 * min)
        assertTrue(tracker.nextSendAllowedAt() <= clock.now())
        tracker.onQueued("b2", BolusOrigin.LOOP, 0.3)
        tracker.onBaseline(firstSnapshot.copy(readAt = clock.now()))
        tracker.onWritten()
        // M still shows the first SMB -> not started, not "delivered"
        assertInstanceOf(ReadStatusAt::class.java, tracker.onStatus(firstSnapshot.copy(readAt = clock.now())))
        val second = tracker.onStatus(applied(0.3, minuteOffset = 3)) as Record
        assertTrue(second.pumpTime != firstRecord.pumpTime)
    }

    @Test
    fun `next bolus waits for the pump minute to change`() {
        sendLoopBolus(0.1)
        tracker.onStatus(applied(0.1))
        assertTrue(tracker.nextSendAllowedAt() > clock.now())
        assertEquals(t0 + min, tracker.nextSendAllowedAt())
    }

    // ---- T8 alarm ---------------------------------------------------------------------------

    @Test
    fun `unknown three times alarms once and keeps blocking`() {
        sendLoopBolus()
        tracker.onConfirmed()
        val a1 = tracker.onStatus(null)
        val a2 = tracker.onStatus(null)
        val a3 = tracker.onStatus(null)
        val a4 = tracker.onStatus(null)
        assertInstanceOf(ReadStatusAt::class.java, a1)
        assertInstanceOf(ReadStatusAt::class.java, a2)
        assertInstanceOf(Alarm::class.java, a3)
        assertInstanceOf(ReadStatusAt::class.java, a4, "alarm is raised only once")
        assertFalse(tracker.canSendNewBolus())
        // later delivered clears it and records once
        assertInstanceOf(Record::class.java, tracker.onStatus(applied(0.5)))
        assertTrue(tracker.canSendNewBolus())
    }

    @Test
    fun `unresolved for ten minutes alarms`() {
        sendLoopBolus()
        tracker.onConfirmed()
        clock.advance(11 * min)
        assertInstanceOf(Alarm::class.java, tracker.onStatus(null))
    }

    @Test
    fun `manual resolution unblocks and records what the user saw`() {
        sendLoopBolus()
        repeat(3) { tracker.onStatus(null) }
        val action = tracker.resolveManually(0.2, pumpTime = 123L)
        assertEquals(Record(0.2, 123L), action)
        assertTrue(tracker.canSendNewBolus())
    }

    // ---- T3 / pump state --------------------------------------------------------------------

    @Test
    fun `pump suspended while delivering makes the bolus unresolved and reads M right away`() {
        sendLoopBolus(1.0)
        tracker.onConfirmed()
        tracker.onStatus(Fixtures.snapshot(1.0, applied(1.0).lastBolusTime, square = 1.0, delivered = 0.4))
        val action = tracker.onPumpState(PumpAnswerState.SUSPENDED)
        assertEquals(ReadStatusAt(clock.now()), action)
        clock.advance(5 * min)
        assertEquals(Record(0.4, applied(0.4).lastBolusTime), tracker.onStatus(applied(0.4)))
        assertEquals(0, tracker.resendCount())
    }

    @Test
    fun `pump bolusing state moves a confirmed bolus to delivering`() {
        sendLoopBolus(1.0)
        tracker.onConfirmed()
        assertInstanceOf(ReadStatusAt::class.java, tracker.onPumpState(PumpAnswerState.BOLUSING))
        assertEquals(BolusState.DELIVERING, tracker.current!!.state)
    }

    @Test
    fun `not started without a baseline never resends - alarm instead (pump clock offset unknown)`() {
        tracker.onQueued("b1", BolusOrigin.LOOP, 0.5)
        tracker.onBaseline(null)
        tracker.onWritten()
        val old = Fixtures.snapshot(0.5, t0 - 30 * min, readAt = clock.now())
        tracker.onStatus(old)
        val action = tracker.onStatus(old)
        assertInstanceOf(Alarm::class.java, action)
        assertEquals(0, tracker.resendCount())
    }

    @Test
    fun `not started while another bolus is in progress waits for it, no attempt counted`() {
        sendLoopBolus(0.5)
        val other = baseline.copy(squareBolusAmount = 2.0, deliveredAmount = 1.5, readAt = clock.now())
        assertEquals(ReadStatusAt(clock.now() + 20_000 + 15_000), tracker.onStatus(other))
        assertEquals(ReadStatusAt(clock.now() + 20_000 + 15_000), tracker.onStatus(other))
        assertEquals(0, tracker.resendCount())
    }

    @Test
    fun `aborted before the dose was written fails without verification`() {
        tracker.onQueued("b1", BolusOrigin.LOOP, 0.5)
        assertEquals(true, tracker.onRejectedBeforeWrite())
        assertTrue(tracker.canSendNewBolus())
    }

    @Test
    fun `rejection after the dose was written is unresolved, not failed`() {
        sendLoopBolus()
        assertEquals(false, tracker.onRejectedBeforeWrite())
        assertEquals(BolusState.UNRESOLVED, tracker.current!!.state)
    }

    @Test
    fun `M without a bolus in flight is only observed`() {
        assertEquals(None, tracker.onStatus(applied(0.5)))
        assertEquals(applied(0.5), tracker.latestSnapshot)
    }
}
