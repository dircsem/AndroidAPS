package app.aaps.pump.medtronic.keys

import app.aaps.pump.medtronic.R
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class MedLinkMedtronicDoublePreferenceKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null
) : DoublePreferenceKey {

    /** 4x: the minimum basal after a bolus applies when the last 5 min delta (mg/dL) is above this. */
    MinBasalAfterBolusDelta("medlink_min_basal_after_bolus_delta", 0.0, -20.0, 50.0, dependency = MedLinkMedtronicBooleanPreferenceKey.MinBasalAfterBolusEnabled, titleResId = R.string.medlink_min_basal_after_bolus_delta, summaryResId = R.string.medlink_min_basal_after_bolus_delta_summary),
}
