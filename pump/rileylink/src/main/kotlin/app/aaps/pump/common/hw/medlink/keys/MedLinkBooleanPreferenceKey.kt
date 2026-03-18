package app.aaps.pump.common.hw.medlink.keys

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
    override val exportable: Boolean = true
) : BooleanPreferenceKey {

    ChangeCannulaAction("pref_medlink_change_cannula",true),
    HandleCannulaChangeEvent("pref_medlink_handle_cannula_change_event",false),
    HandleSensorChangeEvent("pref_medlink_handle_sensor_change_event", false),
    LastBgHistory("pref_medlink_init_commands_last_bg_history", false),
    PreviousBgHistory("pref_medlink_init_commands_previous_bg_history", false),
    LastBolusHistory("pref_medlink_init_commands_last_bolus_history", false),
    PreviousBolusHistory("pref_medlink_init_commands_previous_bolus_history", false),
    ShowReportedBatteryLevel("pref_med_link_show_reported_battery_level", false),
    ForceBolusHistoryReadAfter("medlink_key_force_bolus_history_read", false),
}