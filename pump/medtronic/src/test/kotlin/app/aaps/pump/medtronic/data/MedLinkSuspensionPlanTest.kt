package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkSuspensionPlan.Step
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import org.joda.time.LocalDateTime
import org.joda.time.Minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B14 (field night 2026-11-16, 01:46): 0.32 U/h for 30 min with basal 0.75 asks for 17 min suspended. The old plan
 * builder (`mergeExtraOperations`) dropped every SUSPEND after the first delayed REACTIVATE: about 10 min suspended,
 * so more insulin than the loop asked. A TBR with less than 5 active minutes (10 % for 30 min) gave no plan at all,
 * so the pump ran at 100 %.
 */
class MedLinkSuspensionPlanTest {

    private val start = LocalDateTime(2026, 11, 16, 1, 46, 43)

    private fun minutes(from: LocalDateTime, to: LocalDateTime) = Minutes.minutesBetween(from, to).minutes

    private fun suspendedMinutes(steps: List<Step>): Int =
        steps.chunked(2).sumOf { (s, r) -> minutes(s.at, r.at) }

    /** The rules every plan must follow. */
    private fun assertValid(steps: List<Step>, suspended: Int, duration: Int, from: LocalDateTime = start) {
        val label = "suspended $suspended of $duration min: $steps"
        assertTrue(steps.isNotEmpty(), label)
        assertEquals(0, steps.size % 2, label)
        steps.forEachIndexed { i, step ->
            assertEquals(if (i % 2 == 0) OperationType.SUSPEND else OperationType.REACTIVATE, step.type, label)
        }
        steps.zipWithNext().forEach { (a, b) -> assertTrue(!b.at.isBefore(a.at), label) }
        assertTrue(!steps.first().at.isBefore(from), label)
        // never ends after the TBR, never less suspended than asked (never more insulin)
        assertTrue(!steps.last().at.isAfter(from.plusMinutes(duration)), label)
        assertTrue(suspendedMinutes(steps) >= suspended, label)
        // each suspension and each active part between two suspensions is at least 5 min (fewer STOP / START)
        steps.chunked(2).forEach { (s, r) -> assertTrue(minutes(s.at, r.at) >= MedLinkSuspensionPlan.MIN_SEGMENT_MINUTES, label) }
        steps.drop(1).dropLast(1).chunked(2).forEach { (r, s) -> assertTrue(minutes(r.at, s.at) >= MedLinkSuspensionPlan.MIN_SEGMENT_MINUTES, label) }
    }

    @Test
    fun `field case - 0_32 U per h with basal 0_75 for 30 min - 17 min suspended, not 10`() {
        val suspended = MedLinkSuspensionPlan.suspendedMinutes(30, 0.32 / 0.75)
        assertEquals(17, suspended)
        val plan = MedLinkSuspensionPlan.build(suspended, 30, start)
        assertValid(plan.steps, 17, 30)
        assertEquals(17, suspendedMinutes(plan.steps))
    }

    @Test
    fun `10 percent for 30 min - less than 5 active minutes - suspended the whole time, not 100 percent`() {
        val suspended = MedLinkSuspensionPlan.suspendedMinutes(30, 0.10)
        assertEquals(27, suspended)
        val plan = MedLinkSuspensionPlan.build(suspended, 30, start)
        assertValid(plan.steps, 27, 30)
        assertEquals(listOf(Step(OperationType.SUSPEND, start), Step(OperationType.REACTIVATE, start.plusMinutes(30))), plan.steps)
    }

    @Test
    fun `0 percent - one suspension for the whole TBR`() {
        val plan = MedLinkSuspensionPlan.build(30, 30, start)
        assertEquals(listOf(Step(OperationType.SUSPEND, start), Step(OperationType.REACTIVATE, start.plusMinutes(30))), plan.steps)
    }

    @Test
    fun `83 percent for 30 min - one 5 min suspension, as in the field log`() {
        val suspended = MedLinkSuspensionPlan.suspendedMinutes(30, 0.83)
        assertEquals(5, suspended)
        val plan = MedLinkSuspensionPlan.build(suspended, 30, start)
        assertValid(plan.steps, 5, 30)
        assertEquals(2, plan.steps.size)
        assertEquals(5, plan.suspendMinutes)
        assertEquals(25, plan.activeMinutes)
    }

    @Test
    fun `less than 5 min to suspend - no plan`() {
        assertEquals(4, MedLinkSuspensionPlan.suspendedMinutes(30, 0.87))
        assertTrue(MedLinkSuspensionPlan.build(4, 30, start).steps.isEmpty())
    }

    @Test
    fun `rate above the basal or invalid input - nothing to suspend`() {
        assertEquals(0, MedLinkSuspensionPlan.suspendedMinutes(30, 1.2))
        assertEquals(30, MedLinkSuspensionPlan.suspendedMinutes(30, -0.5))
        assertTrue(MedLinkSuspensionPlan.build(0, 30, start).steps.isEmpty())
        assertTrue(MedLinkSuspensionPlan.build(10, 0, start).steps.isEmpty())
    }

    @Test
    fun `every rate 0 to 95 percent for 30, 60 and 120 min - exact suspended minutes, valid steps`() {
        for (duration in listOf(30, 60, 120)) for (percent in 0..95 step 5) {
            val suspended = MedLinkSuspensionPlan.suspendedMinutes(duration, percent / 100.0)
            val plan = MedLinkSuspensionPlan.build(suspended, duration, start)
            if (suspended < MedLinkSuspensionPlan.MIN_SEGMENT_MINUTES) {
                assertTrue(plan.steps.isEmpty(), "$percent % $duration min")
                continue
            }
            assertValid(plan.steps, suspended, duration)
            val active = duration - suspended
            // exact when the active part can be split in parts of 5 min; else suspended the whole TBR (less insulin)
            assertEquals(if (active < MedLinkSuspensionPlan.MIN_SEGMENT_MINUTES) duration else suspended, suspendedMinutes(plan.steps), "$percent % $duration min")
        }
    }

    @Test
    fun `every minute of the clock - the plan stays valid (start active or suspended)`() {
        for (offset in 0 until 60) {
            val from = start.plusMinutes(offset)
            for ((suspended, duration) in listOf(17 to 30, 12 to 30, 15 to 30, 36 to 60, 5 to 30, 25 to 30)) {
                assertValid(MedLinkSuspensionPlan.build(suspended, duration, from).steps, suspended, duration, from)
            }
        }
    }

    @Test
    fun `start follows the clock - the same answer when planned again in the same minute`() {
        val a = MedLinkSuspensionPlan.build(15, 30, start)
        val b = MedLinkSuspensionPlan.build(15, 30, start.plusSeconds(10))
        assertEquals(a.activeMinutesLeft, b.activeMinutesLeft)
    }

    @Test
    fun `starts active - the first suspension comes after the active minutes left`() {
        // 50 % for 30 min: cycles of 5 suspended + 5 active; minute 46 of the hour is in the active part
        val plan = MedLinkSuspensionPlan.build(15, 30, start)
        val expectedLeft = MedLinkSuspendStartRule.activeMinutesLeft(start.toDate().time, plan.suspendMinutes, plan.activeMinutes)
        assertEquals(expectedLeft, plan.activeMinutesLeft)
        assertEquals(start.plusMinutes(expectedLeft), plan.steps.first().at)
    }

    @Test
    fun `an extension starts at the old end of the plan (T24)`() {
        val oldEnd = start.plusMinutes(30)
        val plan = MedLinkSuspensionPlan.build(8, 15, oldEnd)
        assertValid(plan.steps, 8, 15, oldEnd)
    }
}
