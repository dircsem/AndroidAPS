package app.aaps.pump.medtronic.data.dto

import com.google.gson.annotations.Expose
import org.joda.time.Instant
import org.joda.time.LocalDateTime
import java.util.concurrent.LinkedBlockingDeque


/**
 * used by medlink
 */
class TempBasalMicrobolusOperations {

    private var shouldBeSuspended = false
    var durationInMinutes = 0
        set(value) {
            field = value
            // 4ad: end of the plan, for the plan percentage of the Medtronic tab
            endsAt = if (value > 0) System.currentTimeMillis() + value * 60_000L else 0L
        }

    /** 4ad: when the plan ends (0 = no plan). Set together with [durationInMinutes]. */
    var endsAt = 0L
        private set
    var absoluteRate = 0.0

    //    private static final Logger LOG = LoggerFactory.getLogger(L.PUMPCOMM);
    var operations = LinkedBlockingDeque<TempBasalMicroBolusPair>()
    @Expose
    var remainingOperations = operations.size
        private set
    @Expose
    var totalDosage = 0.0
        private set

    @Expose
    private val nextOperationInterval = 0

    @Expose
    private var suspendedTime: Int? = null

    constructor() {}
    constructor(
        remainingOperations: Int, totalDosage: Double,
        durationInMinutes: Int,
        operations: LinkedBlockingDeque<TempBasalMicroBolusPair>
    ) {
        this.remainingOperations = remainingOperations
        this.totalDosage = totalDosage
        this.operations = operations
        this.durationInMinutes = durationInMinutes
    }

    override fun toString(): String {
        return "TempBasalMicrobolusOperations{" +
                "remainingOperations=" + remainingOperations +
                ", operationDose=" + totalDosage +
                ", nextOperationInterval=" + nextOperationInterval +
                ", operations=" + operations +
                '}'
    }

    @Synchronized
    fun updateOperations(
        remainingOperations: Int,
        operationDose: Double,
        operations: LinkedBlockingDeque<TempBasalMicroBolusPair>,
        suspendedTime: Int?
    ) {
        this.remainingOperations = remainingOperations
        this.suspendedTime = suspendedTime
        totalDosage = operationDose
        this.operations = operations
    }

    /**
     * Bug 4: an op finished. Removes exactly [op] (by identity), never "whatever is at the head":
     * the refresh may already have removed it, or a new plan may have replaced the queue.
     * @return true when [op] was still queued.
     */
    @Synchronized
    fun complete(op: TempBasalMicroBolusPair): Boolean {
        val it = operations.iterator()
        while (it.hasNext()) {
            if (it.next() === op) {
                it.remove()
                return true
            }
        }
        return false
    }

    @Synchronized
    fun clearOperations() {

        operations.clear()
    }

    fun setShouldBeSuspended(suspended: Boolean) {
        shouldBeSuspended = suspended
    }

    fun shouldBeSuspended(): Boolean {
        return shouldBeSuspended || operations.stream().findFirst().let {
            if (it.isPresent) {
                val oper = it.get()
                oper.operationType == TempBasalMicroBolusPair.OperationType.REACTIVATE || absoluteRate == 0.0
            } else {
                false
            }
        }
    }
    /**
     * Release time of the first op when it is a REACTIVATE, epoch 0 for another op, null with no ops.
     * 4ae: was `operations.first`, which threw NoSuchElementException on an empty plan (logs/looplock.log).
     */
    fun reactivateTime(): LocalDateTime? {
        val operation = operations.peekFirst() ?: return null
        return if (operation.operationType == TempBasalMicroBolusPair.OperationType.REACTIVATE) {
            operation.releaseTime
        } else
            Instant
                .ofEpochMilli( 0L ).toDateTime()
                .toLocalDateTime()
    }
}