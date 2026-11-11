package app.aaps.pump.common.hw.medlink.keys

import app.aaps.pump.common.hw.rileylink.R
import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class MedLinkBooleanPreferenceKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null
) : BooleanPreferenceKey {

    ChangeCannulaAction("pref_medlink_change_cannula", true, titleResId = R.string.medlink_change_cannula_action, summaryResId = R.string.medlink_change_cannula_summary),
    HandleCannulaChangeEvent("pref_medlink_handle_cannula_change_event", false, titleResId = R.string.medlink_handle_cannula_change_event),
    HandleSensorChangeEvent("pref_medlink_handle_sensor_change_event", false, titleResId = R.string.medlink_handle_sensor_change_event),
    LastBgHistory("pref_medlink_init_commands_last_bg_history", false, titleResId = R.string.medlink_init_commands_last_bg_history, summaryResId = R.string.medlink_init_commands_last_bg_history_summary),
    PreviousBgHistory("pref_medlink_init_commands_previous_bg_history", false, titleResId = R.string.medlink_init_commands_previous_bg_history, summaryResId = R.string.medlink_init_commands_previous_bg_history_summary),
    LastBolusHistory("pref_medlink_init_commands_last_bolus_history", false, titleResId = R.string.medlink_init_commands_last_bolus_history, summaryResId = R.string.medlink_init_commands_last_bolus_history_summary),
    PreviousBolusHistory("pref_medlink_init_commands_previous_bolus_history", false, titleResId = R.string.medlink_init_commands_previous_bolus_history, summaryResId = R.string.medlink_init_commands_previous_bolus_history_summary),
    ShowReportedBatteryLevel("pref_med_link_show_reported_battery_level", false),
    ForceBolusHistoryReadAfter("medlink_key_force_bolus_history_read", false, titleResId = R.string.medlink_key_force_bolus_history_read),
}