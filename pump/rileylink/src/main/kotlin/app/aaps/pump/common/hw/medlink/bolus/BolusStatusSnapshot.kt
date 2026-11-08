package app.aaps.pump.common.hw.medlink.bolus

/**
 * One parsed `M` (BolusStatus) answer.
 *
 * @param lastBolusAmount   amount of the "last bolus" line
 * @param lastBolusTime     pump time of the "last bolus" line (pump clock, minute resolution)
 * @param squareBolusAmount amount of the "square bolus" line, null when the line is missing
 * @param deliveredAmount   "delivered" of the "square bolus" line, null when the line is missing
 * @param pumpBolusing      the answer explicitly says the pump is bolusing
 * @param readAt            phone time when the answer was read
 */
data class BolusStatusSnapshot(
    val lastBolusAmount: Double,
    val lastBolusTime: Long,
    val squareBolusAmount: Double?,
    val deliveredAmount: Double?,
    val pumpBolusing: Boolean,
    val readAt: Long,
    val raw: String
) {

    /** A bolus is still being delivered: explicit bolusing flag, or a square bolus not delivered yet. */
    val inProgress: Boolean
        get() = pumpBolusing ||
            (squareBolusAmount != null && squareBolusAmount > 0.0 && (deliveredAmount ?: 0.0) < squareBolusAmount - BolusStatusInterpreter.TOLERANCE)
}
