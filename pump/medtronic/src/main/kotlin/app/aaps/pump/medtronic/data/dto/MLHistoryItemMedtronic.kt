package app.aaps.pump.medtronic.data.dto

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.common.hw.medlink.data.MLHistoryItem
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import org.joda.time.LocalDateTime

/**
 * Created by Dirceu on 25/09/20
 * copied from [RLHistoryItemMedtronic]
 * used by medlink
 */
class MLHistoryItemMedtronic(private val medtronicCommandType: MedLinkCommandType?) : MLHistoryItem(
    LocalDateTime(), MLHistoryItemSource.MedtronicCommand, RileyLinkTargetDevice.MedtronicPump
) {
    public override fun getDescription(resourceHelper: ResourceHelper): String? {
        when (this.source) {
            MLHistoryItemSource.MedLink -> return ("State: " + resourceHelper.gs(serviceState!!.resourceId)
                    + (if (this.errorCode == null) "" else ", Error Code: " + errorCode))

            MLHistoryItemSource.MedtronicPump -> return super.getDescription(resourceHelper)

            MLHistoryItemSource.MedtronicCommand -> return medtronicCommandType!!.name

            else -> return "Unknown Description"
        }
    }
}
