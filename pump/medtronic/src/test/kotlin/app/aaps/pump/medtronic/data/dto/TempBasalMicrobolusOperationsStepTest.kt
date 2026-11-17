package app.aaps.pump.medtronic.data.dto

import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import org.joda.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingDeque

/**
 * B12 (field crash 2026-11-16 02:31:50): `operations.isEmpty()` then `operations.first` threw NoSuchElementException
 * when another thread took the last step in between. The app died while the pump was suspended.
 */
class TempBasalMicrobolusOperationsStepTest {

    private val t0 = LocalDateTime(2026, 11, 16, 2, 30)
    private fun op(type: OperationType, minutes: Int) = TempBasalMicroBolusPair(0, 0.0, 0.0, t0.plusMinutes(minutes), type, null)

    private fun plan(vararg ops: TempBasalMicroBolusPair) = TempBasalMicrobolusOperations().apply {
        updateOperations(ops.size, 0.0, LinkedBlockingDeque(ops.toList()), 0)
    }

    @Test
    fun `empty plan - no first step and no exception`() {
        val ops = plan()
        assertNull(ops.firstOrNull())
        assertNull(ops.takeDueStep(t0.plusMinutes(60)))
    }

    @Test
    fun `the last step taken by another thread - firstOrNull is null, no exception`() {
        val reactivate = op(OperationType.REACTIVATE, 1)
        val ops = plan(reactivate)
        assertSame(reactivate, ops.firstOrNull())
        ops.complete(reactivate)
        assertNull(ops.firstOrNull())
    }

    @Test
    fun `due step is taken once and marked as sent`() {
        val suspend = op(OperationType.SUSPEND, 0)
        val ops = plan(suspend, op(OperationType.REACTIVATE, 5))

        assertSame(suspend, ops.takeDueStep(t0.plusSeconds(30)))
        assertTrue(suspend.isCommandIssued)
        // a second refresh thread at the same time does not take it again, and the REACTIVATE is not due yet
        assertNull(ops.takeDueStep(t0.plusSeconds(30)))
    }

    @Test
    fun `a sent step is removed on the next run and the next due step is taken`() {
        val suspend = op(OperationType.SUSPEND, 0)
        val reactivate = op(OperationType.REACTIVATE, 5)
        val ops = plan(suspend, reactivate)

        ops.takeDueStep(t0.plusSeconds(30))
        assertSame(reactivate, ops.takeDueStep(t0.plusMinutes(5).plusSeconds(30)))
        assertEquals(listOf(reactivate), ops.operations.toList())
    }

    @Test
    fun `a step not due yet is not taken`() {
        val ops = plan(op(OperationType.SUSPEND, 10))
        assertNull(ops.takeDueStep(t0.plusMinutes(9)))
        assertEquals(1, ops.operations.size)
    }

    @Test
    fun `removing a sent step removes that step, not an equal one`() {
        // two steps equal by value (same time and type)
        val a = op(OperationType.SUSPEND, 0)
        val b = op(OperationType.SUSPEND, 0)
        val ops = plan(a, b)

        assertSame(a, ops.takeDueStep(t0.plusSeconds(30)))
        assertSame(b, ops.takeDueStep(t0.plusSeconds(30)))
        assertEquals(1, ops.operations.size)
        assertSame(b, ops.firstOrNull())
    }

    @Test
    fun `clearing the plan keeps the time`() {
        val ops = plan(op(OperationType.SUSPEND, 0))
        assertEquals(0L, ops.clearedAt)
        ops.clearOperations()
        assertTrue(ops.clearedAt > 0L)
        assertNull(ops.firstOrNull())
    }
}
