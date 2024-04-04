package info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities

import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkStatusParser
import app.aaps.core.interfaces.pump.DetailedBolusInfo

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.data.MedLinkPartialBolus
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.data.MedLinkPumpStatusCallback
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 15/09/21.
 */
class BolusDeliverCallback(val pumpStatus: MedLinkPumpStatus, val plugin: MedLinkPumpPluginBase,
                           val aapsLogger: AAPSLogger, private val lastBolusInfo: DetailedBolusInfo
) : BaseStringAggregatorCallback() {

    override fun apply(answer: Supplier<Stream<String>>): MedLinkStandardReturn<String> {
        val ans = answer.get().iterator()
        aapsLogger.info(LTag.EVENTS, "bolus delivering")
        aapsLogger.info(LTag.EVENTS, pumpStatus.toString())
        MedLinkStatusParser.parseBolusInfo(ans, pumpStatus)
        aapsLogger.info(LTag.EVENTS, "after parse")
        aapsLogger.info(LTag.EVENTS, pumpStatus.toString())

        if (pumpStatus.lastBolusAmount != null) {
            plugin.handleBolusDelivered(lastBolusInfo)
        }
        return super.apply(answer)
    }
}