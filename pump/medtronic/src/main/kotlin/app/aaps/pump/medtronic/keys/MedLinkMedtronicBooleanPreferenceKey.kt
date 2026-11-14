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

    /** Ask to take over the profile read from the pump when it differs from the AAPS profile. Off by default. */
    AcceptPumpProfile(
        "medlink_accept_pump_profile", false,
        titleResId = R.string.medlink_accept_pump_profile,
        summaryResId = R.string.medlink_accept_pump_profile_summary
    ),
}
