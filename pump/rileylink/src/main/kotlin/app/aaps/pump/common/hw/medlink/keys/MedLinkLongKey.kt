package app.aaps.pump.common.hw.medlink.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class MedLinkLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LastGoodDeviceCommunicationTime("AAPS.RileyLink.lastGoodDeviceCommunicationTime", 0L),
}