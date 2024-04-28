package info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities

import android.os.SystemClock
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.CommandExecutor
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.defs.MedLinkPumpDevice
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkStatusParser

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventDismissBolusProgressIfRunning
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress.t
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.data.MedLinkPartialBolus
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.data.MedLinkPumpStatusCallback

import org.json.JSONObject
import java.util.function.Supplier
import java.util.stream.Stream
import kotlin.math.roundToInt

data class BolusProgressCallback(
    val pumpStatus: MedLinkPumpStatus,
    val resourceHelper: ResourceHelper,
    val rxBus: RxBus,
    val aapsLogger: AAPSLogger,
    val medLinkPumpPlugin: MedLinkPumpDevice,
    val detailedBolusInfo: DetailedBolusInfo
) : BaseStringAggregatorCallback() {

    var commandExecutor: CommandExecutor<String>? = null
    var resend = true;
    fun resend(): Boolean {
        return resend;
    }

    override fun apply(answer: Supplier<Stream<String>>): MedLinkStandardReturn<String> {
        var ans = answer.get().iterator()
        while (ans.hasNext()) {
            val currentLine = ans.next()
            if (currentLine.contains("confirmed")) {
                break
            }
        }
        MedLinkStatusParser.parseBolusInfo(ans, pumpStatus)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + pumpStatus.lastBolusAmount)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + pumpStatus.bolusDeliveredAmount)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + pumpStatus.lastBolusInfo)
        aapsLogger.info(LTag.PUMPBTCOMM, detailedBolusInfo.toString())
        if (pumpStatus.lastBolusAmount != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "lastbolusAmount")
            val bolusEvent = EventOverviewBolusProgress
            bolusEvent.t = t
            bolusEvent.status = resourceHelper.gs(app.aaps.core.ui.R.string.bolus_delivering, pumpStatus.bolusDeliveredAmount, pumpStatus.lastBolusAmount)
            bolusEvent.percent = ((pumpStatus.bolusDeliveredAmount?.div(detailedBolusInfo.insulin))?.times(100)?.roundToInt())?.or(0) ?: 0

            rxBus.send(bolusEvent)
            if (bolusEvent.percent == 100 || pumpStatus.bolusDeliveredAmount == 0.0) {
                aapsLogger.info(LTag.PUMPBTCOMM, "boluscompleted")

                // medLinkPumpPlugin.handleNewTreatmentData(Stream.of(JSONObject(detailedBolusInfo.toJsonString())))

                pumpStatus.lastBolusInfo.let {
                    it?.timestamp = pumpStatus.lastBolusTime?.time ?: it!!.timestamp

                    it?.insulin = pumpStatus.lastBolusAmount ?: it!!.insulin

                    it?.bolusType = detailedBolusInfo.bolusType
                    it?.carbs = detailedBolusInfo.carbs
                    it?.eventType = detailedBolusInfo.eventType
                    aapsLogger.info(LTag.PUMP,it!!.toJsonString())
                    medLinkPumpPlugin.handleNewTreatmentData(Stream.of(JSONObject(it?.toJsonString())))
                }

                SystemClock.sleep(200)
                bolusEvent.status = resourceHelper.gs(app.aaps.core.ui.R.string.bolus_delivering, pumpStatus.lastBolusAmount)
                bolusEvent.percent = 100
                rxBus.send(bolusEvent)
                SystemClock.sleep(1000)

                // rxBus.send(bolusEvent)
                rxBus.send(EventDismissBolusProgressIfRunning(null, pumpStatus.lastBolusTime?.time))
                resend = false
            }
            //else commandExecutor?.clearExecutedCommand()
        }
        return super.apply(answer)
    }

}