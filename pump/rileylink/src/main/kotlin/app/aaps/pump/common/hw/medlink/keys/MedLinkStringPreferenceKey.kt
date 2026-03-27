package app.aaps.pump.common.hw.medlink.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class MedLinkStringPreferenceKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {

    Type("medtronic_history_type",""),
    MacAddress("pref_medlink_mac_address", ""),
    Encoding("pref_medtronic_encoding", "medtronic_pump_encoding_4b6b_rileylink"),
    LastBolusHistory("medlink_init_commands_last_bolus_history", ""),
    PreviousBgHistory("medlink_init_commands_previous_bg_history", ""),
    LastBgHistory("medlink_init_commands_last_bg_history", ""),
    BatteryInfo("pref_medlink_battery_info","")
}