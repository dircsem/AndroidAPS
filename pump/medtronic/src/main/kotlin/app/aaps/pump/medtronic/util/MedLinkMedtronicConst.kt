package app.aaps.pump.medtronic.util

import app.aaps.pump.medtronic.R


/**
 * Created by dirceu on 25/09/20
 * used at medlink
 */
object MedLinkMedtronicConst {
    const val Prefix: String = "AAPS.Medtronic."

    object Prefs {
        val PumpSerial: Int = R.string.medtronic_serial_number
        val PumpType: Int = R.string.medtronic_pump_type
        val PumpFrequency: Int = R.string.medtronic_pump_frequency
        val MaxBolus: Int = R.string.medtronic_pump_max_bolus
        val MaxBasal: Int = R.string.medtronic_pump_max_basal
        val BolusDelay: Int = R.string.medtronic_pump_bolus_delay
        val Encoding: Int = R.string.medtronic_pump_encoding
        val BatteryType: Int = R.string.medtronic_history_type
        val BolusDebugEnabled: Int = R.string.medtronic_bolus_debugging
        val PumpInitCommands: Int = R.string.key_medlink_init_command
    }

    object Statistics {
        const val StatsPrefix: String = "medtronic_"
        val FirstPumpStart: String = Prefix + "first_pump_use"
        val LastGoodPumpCommunicationTime: String = Prefix + "lastGoodPumpCommunicationTime"
        val LastGoodPumpFrequency: String = Prefix + "LastGoodPumpFrequency"
        val TBRsSet: String = StatsPrefix + "tbrs_set"
        val StandardBoluses: String = StatsPrefix + "std_boluses_delivered"
        val SMBBoluses: String = StatsPrefix + "smb_boluses_delivered"
        val LastPumpHistoryEntry: String = StatsPrefix + "pump_history_entry"
        val LastPrime: String = StatsPrefix + "last_sent_prime"
    }
}
