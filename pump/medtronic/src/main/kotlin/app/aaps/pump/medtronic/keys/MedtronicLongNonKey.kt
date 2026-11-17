package app.aaps.pump.medtronic.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey


enum class MedtronicLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    TbrsSet("medtronic_tbrs_set", 0),
    SmbBoluses("medtronic_smb_boluses_delivered", 0),
    StandardBoluses("medtronic_std_boluses_delivered", 0),
    FirstPumpUse("medtronic_first_pump_use", 0),
    LastGoodPumpCommunicationTime("medtronic_lastGoodPumpCommunicationTime", 0L),
    LastPumpHistoryEntry("medtronic_pump_history_entry", 0L),
    LastPrime("medtronic_last_sent_prime", 0L),
    LastRewind("medtronic_last_sent_rewind", 0L),
    LastBatteryChange("medtronic_last_sent_battery_change", 0L),

    /** MedLink: hash of the pump profile the user chose to ignore (not asked again until the pump profile changes). */
    MedLinkIgnoredPumpProfile("medlink_ignored_pump_profile_hash", 0L),

    /** MedLink T21: the 0 % TBR stored while the pump was not reached (old battery). 0 = none. Kept over an app restart. */
    MedLinkZeroTbrTempId("medlink_zero_tbr_temp_id", 0L, exportable = false),
    MedLinkZeroTbrStart("medlink_zero_tbr_start", 0L, exportable = false),
    /** 0 while the pump is still not reached; the reconnect time once the question waits for the user. */
    MedLinkZeroTbrEnd("medlink_zero_tbr_end", 0L, exportable = false),
    /** Reservoir at the start of the outage, in 0.001 U; -1 = unknown. */
    MedLinkZeroTbrReservoirBefore("medlink_zero_tbr_reservoir_before", -1L, exportable = false),

    /**
     * MedLink B12: when the pump confirmed the last automatic STOP (TBR plan). 0 = the last stop was by the user, or
     * the pump runs again. Kept over an app restart: then the app knows that it must start the pump itself.
     */
    MedLinkAutoStopAt("medlink_auto_stop_at", 0L, exportable = false),
}