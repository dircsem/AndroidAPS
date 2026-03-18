package app.aaps.pump.common.hw.medlink.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class MedLinkDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val exportable: Boolean = true
) : DoubleNonPreferenceKey {

    LastGoodDeviceFrequency("AAPS.MedLink.LastGoodDeviceFrequency", 0.0),
}