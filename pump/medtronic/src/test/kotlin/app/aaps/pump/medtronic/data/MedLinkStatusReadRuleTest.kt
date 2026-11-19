package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkStatusReadRule.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MedLinkStatusReadRuleTest {

    private val sec = 1_000L
    private val min = 60 * sec
    private val now = 1_800_000_000_000L
    private val later = listOf(now + 5 * min)

    private fun read(steps: List<Step>, refresh: List<Long?> = later, timeChanged: Boolean = false) =
        MedLinkStatusReadRule.needsRead(now, refresh, steps, timeChanged)

    @Test
    fun `plan steps 10 min ahead - no read`() {
        assertFalse(read(listOf(Step(now + 10 * min, false), Step(now + 20 * min, false))))
    }

    @Test
    fun `no plan and no refresh due - no read`() {
        assertFalse(read(emptyList()))
    }

    @Test
    fun `a step due now - read`() {
        assertTrue(read(listOf(Step(now, false), Step(now + 10 * min, false))))
    }

    @Test
    fun `a step due in less than 30 s - read, the runner starts it now`() {
        assertTrue(read(listOf(Step(now + 29 * sec, false))))
        assertFalse(read(listOf(Step(now + 31 * sec, false))))
    }

    @Test
    fun `a step that is late and not sent - read`() {
        assertTrue(read(listOf(Step(now - 2 * min, false))))
    }

    @Test
    fun `a step already sent - no read`() {
        assertFalse(read(listOf(Step(now - 1 * min, true), Step(now + 10 * min, false))))
    }

    @Test
    fun `a sent step first, the next one due - read`() {
        assertTrue(read(listOf(Step(now - 5 * min, true), Step(now, false))))
    }

    @Test
    fun `a refresh type that is overdue - read`() {
        assertTrue(read(emptyList(), refresh = listOf(now + 5 * min, now - 1 * sec)))
        assertTrue(read(emptyList(), refresh = listOf(now)))
    }

    @Test
    fun `a refresh time that is not set - no read`() {
        assertFalse(read(emptyList(), refresh = listOf(null, now + 1 * min)))
    }

    @Test
    fun `time or time zone changed - read`() {
        assertTrue(read(emptyList(), timeChanged = true))
    }

    // T23: planned status read

    private fun waitMs(statusAgo: Long, startStopAgo: Long? = null) =
        MedLinkStatusReadRule.statusReadWaitMs(now, now - statusAgo, startStopAgo?.let { now - it } ?: 0L)

    @Test
    fun `T23 last status 1 min old - wait 3 min`() {
        assertEquals(3 * min, waitMs(1 * min))
    }

    @Test
    fun `T23 last status 4, 6 or 11 min old - read now`() {
        assertEquals(0L, waitMs(4 * min))
        assertEquals(0L, waitMs(6 * min))
        assertEquals(0L, waitMs(11 * min))
    }

    @Test
    fun `T23 no status yet - read now`() {
        assertEquals(0L, MedLinkStatusReadRule.statusReadWaitMs(now, 0L, now - 1 * min))
    }

    @Test
    fun `T23 right after a STOP or START - no new connection only for S`() {
        assertEquals(3 * min, waitMs(6 * min, startStopAgo = 1 * min))
        assertEquals(0L, waitMs(6 * min, startStopAgo = 5 * min))
    }

    @Test
    fun `T23 a STOP or START never makes the status older than 10 min`() {
        // status 9 min old, START 1 min ago: wait only 1 min (10 min limit), not 3
        assertEquals(1 * min, waitMs(9 * min, startStopAgo = 1 * min))
        assertEquals(0L, waitMs(11 * min, startStopAgo = 0L))
    }

    // T23 leftover: a planned S just before a STOP / START step waits for it (the answer gives the pump state)

    private fun waitWithStep(statusAgo: Long, stepIn: Long) =
        MedLinkStatusReadRule.statusReadWaitMs(now, now - statusAgo, 0L, nextStartStopStepAt = now + stepIn)

    @Test
    fun `T23 step - STOP or START in 90 s - the S waits for its answer, no extra connection`() {
        assertEquals(90_000L + MedLinkStatusReadRule.STEP_ANSWER_MS, waitWithStep(5 * min, 90_000L))
    }

    @Test
    fun `T23 step - more than 2 min away - read now`() {
        assertEquals(0L, waitWithStep(5 * min, 3 * min))
    }

    @Test
    fun `T23 step - never past the 10 min limit - read now`() {
        // status 9 min old, step in 90 s: waiting would make it older than 10 min
        assertEquals(0L, waitWithStep(9 * min, 90_000L))
    }

    @Test
    fun `T23 step - a step in the past (late or failed) does not delay the read`() {
        assertEquals(0L, waitWithStep(5 * min, -30_000L))
    }

    @Test
    fun `T23 step - no step - as before`() {
        assertEquals(0L, MedLinkStatusReadRule.statusReadWaitMs(now, now - 5 * min, 0L, nextStartStopStepAt = 0L))
        assertEquals(3 * min, MedLinkStatusReadRule.statusReadWaitMs(now, now - 1 * min, 0L, nextStartStopStepAt = 0L))
    }
}
