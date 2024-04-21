package app.aaps.core.interfaces.pump

import app.aaps.core.data.iob.EnliteInMemoryGlucoseValue
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import java.time.ZonedDateTime
import java.util.Date

interface MedLinkPumpStatus {

    val lastBolusInfo: DetailedBolusInfo?
    var lastReadingStatus: BGReadingStatus
    var pumpDeviceState: PumpDeviceState
    val batteryRemaining: Int
    val iob: String?
    var lastConnection: Long
    var bolusDeliveredAmount: Double?
    var lastBolusAmount: Double?
    var latestBG: Double
    val lastBatteryChanged: Long
    var isBatteryChanged: Boolean
    var lastDataTime: Long
    var reservoirRemainingUnits: Double
    var batteryVoltage: Double
    var currentBasal: Double
    var activeProfileName: String
    var tempBasalRemainMin: Int
    var tempBasalAmount: Double?
    var yesterdayTotalUnits: Double?
    var dailyTotalUnits: Double?
    var pumpRunningState: PumpRunningState
    var sensorAge: Int?
    var isig: Double?
    var bgReading:  EnliteInMemoryGlucoseValue
    var sensorDataReading: BgSync.BgHistory.BgValue
    var calibrationFactor: Double?
    var nextCalibration: ZonedDateTime?
    var bgAlarmOn: Boolean
    var lastBolusTime: Date?
    var lastBGTimestamp: Long

    abstract fun setLastCommunicationToNow()
}

enum class PumpRunningState(val status: String) {
    Running("normal"),
    Suspended("suspended"),
    Unknow("unknow");
}

enum class BGReadingStatus {
    SUCCESS,
    FAILED,
    UNKNOW
}
