package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkTbrPlanCarry.LastBolus
import app.aaps.pump.medtronic.data.MedLinkTbrPlanCarry.Op
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MedLinkTbrPlanCarryTest {

    private val min = 60_000L
    private val step = 0.1

    /** 15:00 as epoch ms, only the differences matter */
    private val t0 = 1_000_000_000_000L

    private fun at(minutes: Int) = t0 + minutes * min

    /** single bolus plan of a 30 min TBR with 0.15 U/h extra (1.35 on 1.2): 0.1 U at the middle */
    private fun singleBolusPlan(now: Long) = listOf(Op(OperationType.BOLUS, now + 15 * min, 0.1, 0.075))

    @Test
    fun `no old plan and no last bolus - times do not change`() {
        val plan = singleBolusPlan(at(0))
        assertEquals(listOf(at(15)), MedLinkTbrPlanCarry.releaseTimes(plan, null, null, 30, step, at(0)))
    }

    @Test
    fun `same TBR again - first bolus keeps the old time`() {
        // 15:00 plan has the bolus at 15:15, at 15:05 the loop sends the TBR again
        val previous = Op(OperationType.BOLUS, at(15), 0.1, 0.075)
        val times = MedLinkTbrPlanCarry.releaseTimes(singleBolusPlan(at(5)), previous, null, 30, step, at(5))
        assertEquals(listOf(at(15)), times)
    }

    @Test
    fun `TBR sent every 5 min - the first bolus happens at 15_15`() {
        var pending = singleBolusPlan(at(0)).first()
        for (minute in listOf(5, 10, 15)) {
            val times = MedLinkTbrPlanCarry.releaseTimes(singleBolusPlan(at(minute)), pending, null, 30, step, at(minute))
            pending = pending.copy(releaseAt = times.first()!!)
        }
        assertEquals(at(15), pending.releaseAt)
    }

    @Test
    fun `new plan earlier than the old one - new time is kept`() {
        val previous = Op(OperationType.BOLUS, at(20), 0.1, 0.075)
        val plan = listOf(Op(OperationType.BOLUS, at(5), 0.1, 0.1), Op(OperationType.BOLUS, at(10), 0.1, 0.1))
        assertEquals(listOf(at(5), at(10)), MedLinkTbrPlanCarry.releaseTimes(plan, previous, null, 30, step, at(5)))
    }

    @Test
    fun `direction changed - old bolus is not kept`() {
        // the old plan had a bolus pending, the new TBR is below 100 % (suspension plan)
        val previous = Op(OperationType.BOLUS, at(15))
        val plan = listOf(Op(OperationType.SUSPEND, at(10)), Op(OperationType.REACTIVATE, at(20)))
        assertEquals(listOf(at(10), at(20)), MedLinkTbrPlanCarry.releaseTimes(plan, previous, null, 30, step, at(5)))
    }

    @Test
    fun `suspension moves earlier together with its reactivate`() {
        // 15:00 plan for 50 % suspends at 15:10, at 15:10 the loop sends 50 % again: new plan would suspend at 15:20
        val previous = Op(OperationType.SUSPEND, at(10))
        val plan = listOf(
            Op(OperationType.SUSPEND, at(20)), Op(OperationType.REACTIVATE, at(35)),
            Op(OperationType.SUSPEND, at(45)), Op(OperationType.REACTIVATE, at(55))
        )
        val times = MedLinkTbrPlanCarry.releaseTimes(plan, previous, null, 60, step, at(10))
        assertEquals(listOf(at(10), at(25), at(45), at(55)), times)
    }

    @Test
    fun `first operation is a reactivate - nothing changes`() {
        val previous = Op(OperationType.REACTIVATE, at(5))
        val plan = listOf(Op(OperationType.REACTIVATE, at(10)), Op(OperationType.SUSPEND, at(20)))
        assertEquals(listOf(at(10), at(20)), MedLinkTbrPlanCarry.releaseTimes(plan, previous, null, 30, step, at(5)))
    }

    @Test
    fun `spacing guard - next bolus waits dose divided by extra rate`() {
        // 0.1 U given at 15:15, extra 0.15 U/h: next bolus not before 15:55
        val last = LastBolus(at(15), 0.1)
        val times = MedLinkTbrPlanCarry.releaseTimes(singleBolusPlan(at(20)), null, last, 30, step, at(20))
        assertEquals(listOf(at(55)), MedLinkTbrPlanCarry.releaseTimes(singleBolusPlan(at(25)), null, last, 30, step, at(25)))
        // at 15:20 the TBR ends at 15:50, before 15:55: the bolus is dropped from this plan
        assertEquals(listOf(null), times)
    }

    @Test
    fun `spacing guard wins over the old time`() {
        val last = LastBolus(at(15), 0.1)
        val previous = Op(OperationType.BOLUS, at(30), 0.1, 0.075)
        val times = MedLinkTbrPlanCarry.releaseTimes(singleBolusPlan(at(25)), previous, last, 30, step, at(25))
        assertEquals(listOf(at(55)), times)
    }

    @Test
    fun `spacing guard does not move a bolus that is already late enough`() {
        val last = LastBolus(at(-60), 0.1)
        assertEquals(listOf(at(15)), MedLinkTbrPlanCarry.releaseTimes(singleBolusPlan(at(0)), null, last, 30, step, at(0)))
    }

    @Test
    fun `spacing guard moves the next boluses by the same time and drops those after the end`() {
        // 0.3 U in 30 min (0.6 U/h extra): boluses at 0, 10, 20 min. Last bolus 0.1 U at -5 min: first not before +5
        val plan = listOf(
            Op(OperationType.BOLUS, at(0), 0.1, 0.1),
            Op(OperationType.BOLUS, at(10), 0.1, 0.1),
            Op(OperationType.BOLUS, at(20), 0.1, 0.1)
        )
        val times = MedLinkTbrPlanCarry.releaseTimes(plan, null, LastBolus(at(-5), 0.1), 30, step, at(0))
        assertEquals(listOf(at(5), at(15), at(25)), times)

        // last bolus at +1: first at +11, then +21, the third (+31) is after the end of the TBR (+30) and is dropped
        val late = MedLinkTbrPlanCarry.releaseTimes(plan, null, LastBolus(at(1), 0.1), 30, step, at(0))
        assertEquals(listOf(at(11), at(21), null), late)
    }

    @Test
    fun `TBR sent every 5 min for 2 hours - insulin matches the TBR`() {
        // 1.35 U/h on 1.2 U/h for 2 h = 0.3 U extra. Simulate: new plan every 5 min, a bolus is given when due.
        var pending: Op? = null
        var last: LastBolus? = null
        var given = 0.0
        for (minute in 0..120 step 5) {
            val now = at(minute)
            if (pending != null && pending.releaseAt <= now) {
                given += pending.dose
                last = LastBolus(now, pending.dose)
                pending = null
            }
            val plan = singleBolusPlan(now)
            val times = MedLinkTbrPlanCarry.releaseTimes(plan, pending, last, 30, step, now)
            pending = times.first()?.let { plan.first().copy(releaseAt = it) }
        }
        // 0.15 U/h for 2 h = 0.3 U: boluses at 15, 55, 95 min
        assertEquals(0.3, given, 1e-9)
    }

    @Test
    fun `extra rate counts the steps of each bolus`() {
        // a 0.2 U bolus has 2 steps of 0.1 U, calculatedDose is per step
        val ops = listOf(Op(OperationType.BOLUS, at(0), 0.2, 0.12), Op(OperationType.BOLUS, at(10), 0.1, 0.12))
        assertEquals(0.72, MedLinkTbrPlanCarry.extraRatePerHour(ops, 30, step), 1e-9)
    }
}
