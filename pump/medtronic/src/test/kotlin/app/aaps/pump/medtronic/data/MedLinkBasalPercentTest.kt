package app.aaps.pump.medtronic.data

import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.medtronic.data.MedLinkBasalPercent.Op
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MedLinkBasalPercentTest {

    private val min = 60_000L
    private val h18 = 1_000_000_000_000L - 1_000_000_000_000L % 3_600_000L

    @Test
    fun `pump percent follows the running state`() {
        assertThat(MedLinkBasalPercent.pump(PumpRunningState.Running)).isEqualTo(100)
        assertThat(MedLinkBasalPercent.pump(PumpRunningState.Suspended)).isEqualTo(0)
        assertThat(MedLinkBasalPercent.pump(PumpRunningState.TempBasalSuspended)).isEqualTo(0)
        assertThat(MedLinkBasalPercent.pump(PumpRunningState.Unknow)).isNull()
    }

    @Test
    fun `user example - 18h30, basal 1 U per h, 0_75 U delivered is 150 percent`() {
        // pump running since 18:00 (0.5 U of base basal) + 0.25 U of TBR microboluses
        val timeline = listOf(h18 - 10 * min to PumpRunningState.Running)
        val boluses = listOf(h18 + 10 * min to 0.1, h18 + 20 * min to 0.15)
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 1.0, timeline, boluses)).isEqualTo(150)
    }

    @Test
    fun `suspended half of the time is 50 percent`() {
        val timeline = listOf(
            h18 - 5 * min to PumpRunningState.Running,
            h18 + 10 * min to PumpRunningState.Suspended,
            h18 + 25 * min to PumpRunningState.Running
        )
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 1.0, timeline, emptyList())).isEqualTo(50)
    }

    @Test
    fun `microboluses before the hour do not count`() {
        val timeline = listOf(h18 - 30 * min to PumpRunningState.Running)
        val boluses = listOf(h18 - 5 * min to 0.5)
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 1.0, timeline, boluses)).isEqualTo(100)
    }

    @Test
    fun `after an app restart only the known time counts`() {
        // first state known at 18:20, suspended: 0 % over 18:20-18:30, nothing assumed for 18:00-18:20
        val timeline = listOf(h18 + 20 * min to PumpRunningState.Suspended)
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 1.0, timeline, emptyList())).isEqualTo(0)
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 1.0, emptyList(), emptyList())).isNull()
    }

    @Test
    fun `unknown state is left out`() {
        val timeline = listOf(
            h18 to PumpRunningState.Running,
            h18 + 10 * min to PumpRunningState.Unknow,
            h18 + 20 * min to PumpRunningState.Suspended
        )
        // 10 min running + 10 min suspended known: 50 %
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 1.0, timeline, emptyList())).isEqualTo(50)
    }

    @Test
    fun `no base basal gives no percent`() {
        assertThat(MedLinkBasalPercent.delivered(h18, h18 + 30 * min, 0.0, listOf(h18 to PumpRunningState.Running), emptyList())).isNull()
        assertThat(MedLinkBasalPercent.plan(h18, h18 + 30 * min, 0.0, false, listOf(Op(h18, OperationType.BOLUS, 0.1)))).isNull()
    }

    @Test
    fun `plan of suspensions - 15 of 30 min suspended is 50 percent`() {
        val ops = listOf(
            Op(h18 + 5 * min, OperationType.SUSPEND, 0.0),
            Op(h18 + 20 * min, OperationType.REACTIVATE, 0.0)
        )
        assertThat(MedLinkBasalPercent.plan(h18, h18 + 30 * min, 1.0, false, ops)).isEqualTo(50)
    }

    @Test
    fun `plan starting suspended counts the pump state now`() {
        val ops = listOf(Op(h18 + 10 * min, OperationType.REACTIVATE, 0.0))
        // suspended 10 of 30 min
        assertThat(MedLinkBasalPercent.plan(h18, h18 + 30 * min, 1.0, true, ops)).isEqualTo(67)
    }

    @Test
    fun `plan of microboluses above 100 percent`() {
        // base 1 U/h over 30 min = 0.5 U + 0.5 U of microboluses = 200 %
        val ops = (0 until 5).map { Op(h18 + it * 6 * min, OperationType.BOLUS, 0.1) }
        assertThat(MedLinkBasalPercent.plan(h18, h18 + 30 * min, 1.0, false, ops)).isEqualTo(200)
    }

    @Test
    fun `no plan gives no percent`() {
        assertThat(MedLinkBasalPercent.plan(h18, 0L, 1.0, false, listOf(Op(h18, OperationType.BOLUS, 0.1)))).isNull()
        assertThat(MedLinkBasalPercent.plan(h18, h18 + 30 * min, 1.0, false, emptyList())).isNull()
    }

    @Test
    fun `timeline records changes only and drops old ones`() {
        val timeline = MedLinkBasalPercent.Timeline(keepMs = 60 * min)
        timeline.record(h18, PumpRunningState.Running)
        timeline.record(h18 + 5 * min, PumpRunningState.Running)
        assertThat(timeline.snapshot()).hasSize(1)
        timeline.record(h18 + 10 * min, PumpRunningState.Suspended)
        timeline.record(h18 + 20 * min, PumpRunningState.Running)
        timeline.record(h18 + 90 * min, PumpRunningState.Suspended)
        // 18:10 and older are gone; 18:20 stays as the state at the start of the kept hour
        assertThat(timeline.snapshot().map { it.first }).containsExactly(h18 + 20 * min, h18 + 90 * min).inOrder()
    }
}
