package app.aaps.core.interfaces.pump

import app.aaps.core.data.iob.EnliteInMemoryGlucoseValue
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import java.time.ZonedDateTime
import java.util.Date

interface MedLinkPumpStatus {

    abstract val lastBolusInfo: DetailedBolusInfo?
    abstract var lastReadingStatus: BGReadingStatus
    var pumpDeviceState: PumpDeviceState
    abstract val batteryRemaining: Int
    abstract val iob: String?
    abstract var lastConnection: Long
    abstract var bolusDeliveredAmount: Double?
    abstract var lastBolusAmount: Double?
    abstract var latestBG: Double
    abstract val lastBatteryChanged: Long
    abstract var isBatteryChanged: Boolean
    abstract var lastDataTime: Long
    abstract var reservoirRemainingUnits: Double
    abstract var batteryVoltage: Double
    var currentBasal: Double
    abstract var activeProfileName: String
    abstract var tempBasalRemainMin: Int
    abstract var tempBasalAmount: Double?
    abstract var yesterdayTotalUnits: Double?
    abstract var dailyTotalUnits: Double?
    abstract var pumpRunningState: PumpRunningState
    abstract var sensorAge: Int?
    abstract var isig: Double?
    abstract var bgReading:  EnliteInMemoryGlucoseValue
    var sensorDataReading: BgSync.BgHistory.BgValue
    var calibrationFactor: Double?
    abstract var nextCalibration: ZonedDateTime?
    abstract var bgAlarmOn: Boolean
    abstract var lastBolusTime: Date?
    abstract var lastBGTimestamp: Long

    fun setLastCommunicationToNow()
}

enum class PumpRunningState(val status: String) {
    Running("normal"),
    Suspended("suspended");
}

enum class BGReadingStatus {
    SUCCESS,
    FAILED
}
