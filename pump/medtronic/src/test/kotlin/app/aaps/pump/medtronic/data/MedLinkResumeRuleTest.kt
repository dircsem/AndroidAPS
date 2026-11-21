package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkResumeRule.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B12 (field night 2026-11-16): the app crashed at 02:31:50 while the TBR plan had stopped the pump. The plan lived only
 * in memory, so after the restart nothing started the pump again (02:32 -> 06:00). The status read saw the pump
 * suspended 61 times. This rule decides what the status read does then.
 */
class MedLinkResumeRuleTest {

    private val min = 60_000L
    private val now = 1_000_000_000L

    private fun decide(
        suspended: Boolean = true,
        autoStopAt: Long = now - 6 * min,
        planHasSteps: Boolean = false,
        nextReactivateAt: Long = 0L,
        planClearedAt: Long = 0L,
        zeroTbrLimitActive: Boolean = false,
        bgMgdl: Double? = null,
        lowMarkMgdl: Double = 72.0,
        lastLoopRunAt: Long = 0L,
        databaseTbrEndAt: Long = 0L,
    ) = MedLinkResumeRule.decide(
        now, suspended, autoStopAt, planHasSteps, nextReactivateAt, planClearedAt, zeroTbrLimitActive, bgMgdl, lowMarkMgdl, lastLoopRunAt,
        databaseTbrEndAt
    )

    @Test
    fun `field case - suspended by the plan, plan lost in a restart - start`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min))
    }

    @Test
    fun `pump running - nothing`() {
        assertEquals(Action.NONE, decide(suspended = false))
    }

    @Test
    fun `last stop by the user or on the pump - never started by the app, no alarm`() {
        assertEquals(Action.NONE, decide(autoStopAt = 0L))
        assertEquals(Action.NONE, decide(autoStopAt = 0L, planClearedAt = now - 60 * min))
    }

    @Test
    fun `T21 waits for the user answer - nothing`() {
        assertEquals(Action.NONE, decide(zeroTbrLimitActive = true, autoStopAt = now - 60 * min))
    }

    @Test
    fun `automatic stop just now - wait, a new plan can be on its way`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 4 * min))
    }

    @Test
    fun `plan cleared just now - wait, the new plan is being built`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 60 * min, planClearedAt = now - 1 * min))
    }

    @Test
    fun `plan with a REACTIVATE still to come - the plan starts the pump`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 20 * min, planHasSteps = true, nextReactivateAt = now + 10 * min))
    }

    @Test
    fun `plan REACTIVATE late by less than the margin - wait`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 20 * min, planHasSteps = true, nextReactivateAt = now - 9 * min))
    }

    @Test
    fun `plan REACTIVATE late by more than the margin - start and alarm`() {
        assertEquals(Action.ALARM, decide(autoStopAt = now - 20 * min, planHasSteps = true, nextReactivateAt = now - 11 * min))
    }

    @Test
    fun `plan with steps but no REACTIVATE - same as no plan`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, planHasSteps = true, nextReactivateAt = 0L))
    }

    // B15: the guard outside the app (Android alarm), for when the app is dead or frozen

    @Test
    fun `guard - plan REACTIVATE to come - alarm 10 min after it`() {
        assertEquals(now + 40 * min, MedLinkResumeRule.guardAlarmAt(autoStopAt = now, nextReactivateAt = now + 30 * min, now = now))
    }

    @Test
    fun `guard - no REACTIVATE (plan lost or none) - 15 min after the stop, but never sooner than 20 min after the app was alive`() {
        assertEquals(now + 20 * min, MedLinkResumeRule.guardAlarmAt(autoStopAt = now, nextReactivateAt = 0L, now = now))
        assertEquals(now + 15 * min, MedLinkResumeRule.guardAlarmAt(autoStopAt = now, nextReactivateAt = 0L, now = now - 10 * min))
    }

    @Test
    fun `guard - a REACTIVATE before the stop (old step) does not count`() {
        assertEquals(now + 15 * min, MedLinkResumeRule.guardAlarmAt(autoStopAt = now, nextReactivateAt = now - 1 * min, now = now - 10 * min))
    }

    @Test
    fun `guard - no automatic stop - no guard`() {
        assertEquals(0L, MedLinkResumeRule.guardAlarmAt(autoStopAt = 0L, nextReactivateAt = now + 30 * min, now = now))
    }

    @Test
    fun `no plan and still suspended long after the stop - the start did not work - alarm`() {
        assertEquals(Action.ALARM, decide(autoStopAt = now - 15 * min))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 4 * 60 * min))
    }

    // ---- low BG: wait for the loop (user, 2026-11-19) ----

    @Test
    fun `restart at a low BG - no loop run yet - wait, the loop may want the pump stopped`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 6 * min, bgMgdl = 65.0))
        assertEquals(Action.NONE, decide(autoStopAt = now - 14 * min, bgMgdl = 65.0))
    }

    @Test
    fun `low BG - the loop does not run - alarm at the normal alarm time, but no START (user, 2026-11-19)`() {
        assertEquals(Action.ALARM_ONLY, decide(autoStopAt = now - 15 * min, bgMgdl = 65.0))
        assertEquals(Action.ALARM_ONLY, decide(autoStopAt = now - 120 * min, bgMgdl = 65.0))
    }

    @Test
    fun `low BG - the loop ran after the stop - the normal rule again`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, bgMgdl = 65.0, lastLoopRunAt = now - 1 * min))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 20 * min, bgMgdl = 65.0, lastLoopRunAt = now - 1 * min))
    }

    @Test
    fun `low BG - a loop run before the stop does not count`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 6 * min, bgMgdl = 65.0, lastLoopRunAt = now - 7 * min))
    }

    @Test
    fun `low BG - a loop run before the last plan change does not count`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 30 * min, planClearedAt = now - 6 * min, bgMgdl = 65.0, lastLoopRunAt = now - 7 * min))
    }

    @Test
    fun `BG at or above the low mark - the normal rule`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, bgMgdl = 72.0))
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, bgMgdl = 150.0))
    }

    @Test
    fun `no BG or old BG - the normal rule (user, 2026-11-19), a CGM gap does not keep the pump stopped`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, bgMgdl = null))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 20 * min, bgMgdl = null))
    }

    @Test
    fun `low BG - a plan REACTIVATE far too late - no START before the loop, alarm only`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 40 * min, planHasSteps = true, nextReactivateAt = now - 5 * min, bgMgdl = 60.0))
        assertEquals(Action.ALARM_ONLY, decide(autoStopAt = now - 40 * min, planHasSteps = true, nextReactivateAt = now - 15 * min, bgMgdl = 60.0))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 40 * min, planHasSteps = true, nextReactivateAt = now - 15 * min, bgMgdl = 60.0, lastLoopRunAt = now - 2 * min))
    }

    // ---- restart: the AAPS TBR below 100 % is still running (field 2026-11-19 14:15-14:31) ----

    @Test
    fun `field case - 0 percent TBR 14_15 for 30 min, crash 14_18, read 14_24 - no START before the TBR end`() {
        // now = 14:24:25; stop 14:15:32 (8 min 53 s ago); TBR end 14:45:32 (21 min 7 s later)
        val stop = now - (8 * min + 53_000L)
        val tbrEnd = stop + 30 * min
        assertEquals(Action.NONE, decide(autoStopAt = stop, databaseTbrEndAt = tbrEnd))
    }

    @Test
    fun `AAPS TBR below 100 percent still running - also no alarm, also long after the stop`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 25 * min, databaseTbrEndAt = now + 1 * min))
    }

    @Test
    fun `after the AAPS TBR end - counted from the TBR end, START only after a loop run after the end`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 40 * min, databaseTbrEndAt = now - 4 * min, lastLoopRunAt = now - 1 * min))
        assertEquals(Action.START, decide(autoStopAt = now - 40 * min, databaseTbrEndAt = now - 5 * min, lastLoopRunAt = now - 1 * min))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 50 * min, databaseTbrEndAt = now - 15 * min))
    }

    @Test
    fun `field 16_59 - TBR ended 16_55, stop at 16_25 - no alarm, wait for the loop (it asked 0 percent again at 17_00)`() {
        // now = 16:59:57: before the fix the rule counted from the stop at 16:25 (35 min): ALARM
        val end = now - (4 * min + 57_000L)
        val stop = end - 30 * min
        assertEquals(Action.NONE, decide(autoStopAt = stop, databaseTbrEndAt = end, lastLoopRunAt = end - 5 * min))
    }

    @Test
    fun `after the AAPS TBR end - the loop did not run after it - wait, alarm (with START) 15 min after the end`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 60 * min, databaseTbrEndAt = now - 10 * min, lastLoopRunAt = now - 11 * min))
        assertEquals(Action.ALARM, decide(autoStopAt = now - 60 * min, databaseTbrEndAt = now - 15 * min, lastLoopRunAt = now - 16 * min))
    }

    @Test
    fun `an AAPS TBR end before the stop is an old one - not used`() {
        assertEquals(Action.START, decide(autoStopAt = now - 6 * min, databaseTbrEndAt = now - 20 * min))
    }

    @Test
    fun `a plan with a REACTIVATE still decides first`() {
        assertEquals(Action.ALARM, decide(autoStopAt = now - 40 * min, planHasSteps = true, nextReactivateAt = now - 11 * min, databaseTbrEndAt = now + 10 * min))
    }

    @Test
    fun `low BG and the AAPS TBR still running - wait`() {
        assertEquals(Action.NONE, decide(autoStopAt = now - 20 * min, bgMgdl = 65.0, databaseTbrEndAt = now + 10 * min))
    }

    @Test
    fun `lowTbrEndAt - only a TBR below 100 percent counts`() {
        val start = now - 9 * min
        val d = 30 * min
        assertEquals(start + d, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 0.0, isAbsolute = false, basalUh = 1.2))
        assertEquals(start + d, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 40.0, isAbsolute = false, basalUh = 1.2))
        assertEquals(0L, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 100.0, isAbsolute = false, basalUh = 1.2))
        assertEquals(0L, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 150.0, isAbsolute = false, basalUh = 1.2))
        assertEquals(start + d, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 0.0, isAbsolute = true, basalUh = 1.2))
        assertEquals(start + d, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 0.5, isAbsolute = true, basalUh = 1.2))
        assertEquals(0L, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 1.3, isAbsolute = true, basalUh = 1.2))
        // no profile basal: an absolute 0 U/h still counts, any other absolute rate can not be compared
        assertEquals(start + d, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 0.0, isAbsolute = true, basalUh = null))
        assertEquals(0L, MedLinkResumeRule.lowTbrEndAt(start, d, rate = 0.5, isAbsolute = true, basalUh = null))
    }

    @Test
    fun `guard - with no plan REACTIVATE the AAPS TBR end is used`() {
        val stop = now - 9 * min
        assertEquals(now + 21 * min + MedLinkResumeRule.ALARM_MARGIN_MS, MedLinkResumeRule.guardAlarmAt(stop, now + 21 * min, now))
    }

    // ---- B15 limits (user, 2026-11-19: no extra alarms at night) ----

    @Test
    fun `guard - AAPS alive - the alarm always stays 20 min ahead, it never sounds while AAPS works`() {
        // plan REACTIVATE 15 min late, the app still works: the in-app rule decides, the guard waits
        assertEquals(now + 20 * min, MedLinkResumeRule.guardAlarmAt(autoStopAt = now - 60 * min, nextReactivateAt = now - 15 * min, now = now))
    }

    @Test
    fun `guard - AAPS dead after its last sign of life - the later of the plan time and 20 min after that sign`() {
        val lastAlive = now - 30 * min
        // plan REACTIVATE 10 min after the last sign of life: 10 min after the REACTIVATE (later than 20 min after)
        assertEquals(lastAlive + 30 * min, MedLinkResumeRule.guardAlarmAt(lastAlive - 5 * min, lastAlive + 20 * min, lastAlive))
        // plan REACTIVATE right after it: 20 min after the last sign of life
        assertEquals(lastAlive + 20 * min, MedLinkResumeRule.guardAlarmAt(lastAlive - 5 * min, lastAlive + 1 * min, lastAlive))
    }

    @Test
    fun `guard - at most one alarm for each automatic STOP, a Silence 30 min reminder always shows`() {
        val stop = now - 40 * min
        assertTrue(MedLinkResumeRule.guardShouldShow(stop, firedForStop = 0L, isSnooze = false))
        assertFalse(MedLinkResumeRule.guardShouldShow(stop, firedForStop = stop, isSnooze = false))
        assertTrue(MedLinkResumeRule.guardShouldShow(stop, firedForStop = stop, isSnooze = true))
        // a new automatic STOP: a new alarm is allowed
        assertTrue(MedLinkResumeRule.guardShouldShow(now - 5 * min, firedForStop = stop, isSnooze = false))
    }
}
