package app.aaps.pump.medtronic.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.pump.medtronic.R

enum class MedLinkMedtronicBooleanPreferenceKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val exportable: Boolean = true,
    override val hideParentScreenIfHidden: Boolean = false,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null
) : BooleanPreferenceKey {

    /** 4x: turns on the minimum basal after a manual bolus. Off by default; its options show only when it is on. */
    MinBasalAfterBolusEnabled(
        "medlink_min_basal_after_bolus_enabled", false,
        titleResId = R.string.medlink_min_basal_after_bolus_enabled,
        summaryResId = R.string.medlink_min_basal_after_bolus_enabled_summary
    ),

    /** T20: alarm when the pump is not reached several times in a row. On by default. */
    ConnectionAlarm(
        "medlink_connection_alarm", true,
        titleResId = R.string.medlink_connection_alarm,
        summaryResId = R.string.medlink_connection_alarm_summary
    ),

    /** T20: the alarm plays the AAPS alarm sound (full screen alarm). Off: notification (and flashlight) only. */
    ConnectionAlarmSound(
        "medlink_connection_alarm_sound", true,
        dependency = ConnectionAlarm,
        titleResId = R.string.medlink_connection_alarm_sound,
        summaryResId = R.string.medlink_connection_alarm_sound_summary
    ),

    /** T20: the alarm blinks the phone flashlight. */
    ConnectionAlarmTorch(
        "medlink_connection_alarm_torch", true,
        dependency = ConnectionAlarm,
        titleResId = R.string.medlink_connection_alarm_torch,
        summaryResId = R.string.medlink_connection_alarm_torch_summary
    ),
}
