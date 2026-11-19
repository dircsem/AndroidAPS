package app.aaps.pump.medtronic.keys

import app.aaps.pump.medtronic.R
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey

enum class MedLinkMedtronicIntPreferenceKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int = Int.MIN_VALUE,
    override val max: Int = Int.MAX_VALUE,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null
) : IntPreferenceKey {

    /** 4x: minimum basal (% of the pump basal) after a manual bolus while BG rises; 0 = off. */
    MinBasalAfterBolusPercent("medlink_min_basal_after_bolus_percent", 0, min = 0, max = 100, dependency = MedLinkMedtronicBooleanPreferenceKey.MinBasalAfterBolusEnabled, titleResId = R.string.medlink_min_basal_after_bolus_percent, summaryResId = R.string.medlink_min_basal_after_bolus_percent_summary),
    /** 4x: how long after a manual bolus the minimum basal applies. */
    MinBasalAfterBolusMinutes("medlink_min_basal_after_bolus_minutes", 180, min = 15, max = 720, dependency = MedLinkMedtronicBooleanPreferenceKey.MinBasalAfterBolusEnabled, titleResId = R.string.medlink_min_basal_after_bolus_minutes, summaryResId = R.string.medlink_min_basal_after_bolus_minutes_summary),
    /** 4y: no suspension when the MedLink or the phone battery is at or below this %. */
    MinBatteryForSuspend("medlink_min_battery_for_suspend", 5, min = 1, max = 50, titleResId = R.string.medlink_min_battery_for_suspend, summaryResId = R.string.medlink_min_battery_for_suspend_summary),
    /**
     * 4aa: tries of a bolus, START or STOP that the MedLink confirms but the pump does not apply; the same number of
     * such commands in a row shows the "alarm on the pump screen" message.
     */
    CommandAttempts("medlink_command_attempts", 5, min = 1, max = 10, titleResId = R.string.medlink_command_attempts, summaryResId = R.string.medlink_command_attempts_summary),
    /** Minutes used for the TBR rate on the main screen: basal delivered in this time, from the database (microboluses and suspensions included). */
    TbrDisplayMinutes("medlink_tbr_display_minutes", 30, min = 5, max = 120, titleResId = R.string.medlink_tbr_display_minutes, summaryResId = R.string.medlink_tbr_display_minutes_summary),
    /**
     * Minutes between two TBR boluses: the TBR plan spaces its boluses by it, and a TBR bolus is not sent when the last
     * TBR or manual bolus was less than this ago. SMBs keep the SMB interval of the loop. 5 = the old fixed value;
     * 2 is the lowest (user, 2026-11-19): the total insulin stays the same, the boluses only come more often.
     */
    TbrBolusInterval("medlink_tbr_bolus_interval", 5, min = 2, max = 15, titleResId = R.string.medlink_tbr_bolus_interval, summaryResId = R.string.medlink_tbr_bolus_interval_summary),
    /** T20: failed pump connections in a row before the alarm. */
    ConnectionAlarmFailures("medlink_connection_alarm_failures", 4, min = 1, max = 10, dependency = MedLinkMedtronicBooleanPreferenceKey.ConnectionAlarm, titleResId = R.string.medlink_connection_alarm_failures, summaryResId = R.string.medlink_connection_alarm_failures_summary),

    /** T19: hours between two timed pump profile reads (E + F); 0 = no timed read (still at app start, Profiles screen, basal differs). */
    ProfileReadIntervalHours("medlink_profile_read_interval_hours", 6, min = 0, max = 24, titleResId = R.string.medlink_profile_read_interval, summaryResId = R.string.medlink_profile_read_interval_summary),

}
