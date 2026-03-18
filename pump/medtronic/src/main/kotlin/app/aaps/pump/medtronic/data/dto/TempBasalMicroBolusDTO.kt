package app.aaps.pump.medtronic.data.dto

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.gson.annotations.Expose

/**
 * used by medlink
 */
class TempBasalMicroBolusDTO : TempBasalPair {
    @Expose
    var endTimeInSeconds: Int = 0

    @Expose
    var sartTimeInSeconds: Int = 0

    constructor(rateByte: Byte, startTimeByte: Int, isPercent: Boolean) : super(
        rateByte,
        startTimeByte,
        isPercent
    )

    constructor(aapsLogger: AAPSLogger, response: ByteArray) : super(aapsLogger, response)

    constructor(insulinRate: Double, isPercent: Boolean, durationMinutes: Int) : super(
        insulinRate,
        isPercent,
        durationMinutes
    )

    constructor(
        profileDosage: Double, isPercent: Boolean, durationMinutes: Int,
        startTimeInSeconds: Int, endTimeInSeconds: Int
    ) : super(profileDosage, isPercent, durationMinutes) {
        this.sartTimeInSeconds = startTimeInSeconds
        this.endTimeInSeconds = endTimeInSeconds
    }
}
