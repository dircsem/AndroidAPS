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
    MinBasalAfterBolusPercent("medlink_min_basal_after_bolus_percent", 0, min = 0, max = 100, titleResId = R.string.medlink_min_basal_after_bolus_percent, summaryResId = R.string.medlink_min_basal_after_bolus_percent_summary),
    /** 4x: how long after a manual bolus the minimum basal applies. */
    MinBasalAfterBolusMinutes("medlink_min_basal_after_bolus_minutes", 180, min = 15, max = 720, titleResId = R.string.medlink_min_basal_after_bolus_minutes, summaryResId = R.string.medlink_min_basal_after_bolus_minutes_summary),
    /** 4y: no suspension when the MedLink or the phone battery is at or below this %. */
    MinBatteryForSuspend("medlink_min_battery_for_suspend", 5, min = 1, max = 50, titleResId = R.string.medlink_min_battery_for_suspend, summaryResId = R.string.medlink_min_battery_for_suspend_summary),
    /**
     * 4aa: tries of a bolus, START or STOP that the MedLink confirms but the pump does not apply; the same number of
     * such commands in a row shows the "alarm on the pump screen" message.
     */
    CommandAttempts("medlink_command_attempts", 5, min = 1, max = 10, titleResId = R.string.medlink_command_attempts, summaryResId = R.string.medlink_command_attempts_summary),
}
