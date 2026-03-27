package app.aaps.pump.common.hw.medlink.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class MedLinkStringKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    Name("pref_medlink_name", ""),
    BatteryInfo("key_medlink_battery_info", "Age"),
    CalibrationFirstAlarm("med_link_calibration_first_alarm","120"),
    CalibrationSecondAlarm("med_link_calibration_second_alarm","120"),
    BatteryInfoShouldBeDisplayed("","Age"),


}