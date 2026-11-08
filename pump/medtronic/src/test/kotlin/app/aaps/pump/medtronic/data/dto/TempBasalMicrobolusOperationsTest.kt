package app.aaps.pump.medtronic.data.dto

import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import org.joda.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingDeque

/**
 * Bug 4 (field log 2026-05-01 11:18 and 13:30): the SUSPEND op was removed by the refresh while STOP was still
 * running, then the stopPump callback called poll() again and removed the next op, the REACTIVATE.
 * The pump stayed suspended until the following REACTIVATE (24 min instead of 10, 52 min instead of 30).
 */
class TempBasalMicrobolusOperationsTest {

    private val t0 = LocalDateTime(2026, 5, 1, 11, 18)
    private fun op(type: OperationType, minutes: Int) = TempBasalMicroBolusPair(0, 0.0, 0.0, t0.plusMinutes(minutes), type, null)

    private fun plan(vararg ops: TempBasalMicroBolusPair) = TempBasalMicrobolusOperations().apply {
        updateOperations(ops.size, 0.0, LinkedBlockingDeque(ops.toList()), 0)
    }

    @Test
    fun `completing an op already removed by the refresh never removes the next REACTIVATE`() {
        val suspend = op(OperationType.SUSPEND, 0)
        val reactivate = op(OperationType.REACTIVATE, 10)
        val ops = plan(suspend, reactivate, op(OperationType.SUSPEND, 15), op(OperationType.REACTIVATE, 25))

        ops.operations.poll()                 // refresh: issued head removed while STOP is still running
        assertFalse(ops.complete(suspend))    // stopPump callback arrives late

        assertEquals(3, ops.operations.size)
        assertTrue(ops.operations.first === reactivate)
    }

    @Test
    fun `completing the head removes only that op`() {
        val suspend = op(OperationType.SUSPEND, 0)
        val reactivate = op(OperationType.REACTIVATE, 10)
        val ops = plan(suspend, reactivate)

        assertTrue(ops.complete(suspend))
        assertEquals(listOf(reactivate), ops.operations.toList())
    }

    @Test
    fun `a late callback from an old plan does not touch a new plan`() {
        val oldSuspend = op(OperationType.SUSPEND, 0)
        val ops = plan(oldSuspend, op(OperationType.REACTIVATE, 10))
        val newSuspend = op(OperationType.SUSPEND, 1)
        ops.updateOperations(2, 0.0, LinkedBlockingDeque(listOf(newSuspend, op(OperationType.REACTIVATE, 31))), 0)

        assertFalse(ops.complete(oldSuspend))
        assertEquals(2, ops.operations.size)
        assertTrue(ops.operations.first === newSuspend)
    }

    @Test
    fun `complete matches by identity, not by value`() {
        val a = op(OperationType.BOLUS, 5)
        val twin = op(OperationType.BOLUS, 5)
        val ops = plan(twin)

        assertFalse(ops.complete(a))
        assertEquals(1, ops.operations.size)
    }
}
