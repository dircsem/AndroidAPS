package app.aaps.pump.medtronic.data.dto

import com.google.gson.annotations.Expose
import org.joda.time.LocalDateTime
import java.util.Objects

/**
 * used by medlink
 */
class TempBasalMicroBolusPair(
    @field:Expose val duration: Int,
    @field:Expose val dose: Double,
    @field:Expose val calculatedDose: Double,
    @field:Expose var releaseTime: LocalDateTime,
    @field:Expose val operationType: OperationType,
    val callback: Function1<*, *>?
) : TempBasalPair(
    dose, false,
    duration
) {
    var isCommandIssued: Boolean = false

    //    public TempBasalMicroBolusPair(Integer duration, Double dose, Double calculatedDose,
    //                                   LocalDateTime releaseTime, OperationType operationType,
    //                                   Function1 callback) {
    //        this(duration, dose, calculatedDose, releaseTime,
    //                operationType, callback);
    //    }
    @Expose
    val interval: Int = 0

    fun delayInMinutes(delay: Int) {
        this.releaseTime = this.releaseTime.plusMinutes(delay)
    }

    fun setReleaseTime(delay: Int) {
        this.releaseTime = LocalDateTime.now().plusMinutes(delay)
    }

    enum class OperationType {
        BOLUS,
        SUSPEND,
        REACTIVATE
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as TempBasalMicroBolusPair
        return callback == that.callback &&
                interval == that.interval &&
                duration == that.duration &&
                Objects.equals(dose, that.dose) &&
                Objects.equals(calculatedDose, that.calculatedDose) &&
                releaseTime == that.releaseTime && operationType == that.operationType
    }

    override fun hashCode(): Int {
        return Objects.hash(
            callback,
            interval,
            duration,
            dose,
            calculatedDose,
            releaseTime,
            operationType
        )
    }

    val delta: Double
        get() = dose - calculatedDose

    public override fun toString(): String {
        return "TempBasalMicroBolusPair{" +
                "callback=" + callback +
                ", interval=" + interval +
                ", duration=" + duration +
                ", dose=" + dose +
                ", calculatedDose=" + calculatedDose +
                ", releaseTime=" + releaseTime +
                ", operationType=" + operationType +
                '}'
    }

    fun toStringView(): String {
        var result = ""
        val release = releaseTime.toString("HH:mm")
        when (operationType) {
            OperationType.BOLUS -> {
                result = "Bolus: " + dose +
                        "u, at=" + release
            }

            OperationType.SUSPEND -> {
                result = "Suspend: at=" + release
            }

            OperationType.REACTIVATE -> {
                result = "Activate: at=" + release
            }
        }
        return result
    }

    fun decreaseDosage(toDecrease: Double): TempBasalMicroBolusPair {
        return TempBasalMicroBolusPair(
            duration, dose - toDecrease, calculatedDose,
            releaseTime, operationType, callback
        )
    }
}
