package app.aaps.pump.common.hw.medlink.activities

import android.os.SystemClock
import app.aaps.pump.common.hw.medlink.ble.CommandExecutor
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpDevice
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventDismissBolusProgressIfRunning
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress

import org.json.JSONObject
import java.util.Optional
import java.util.function.Supplier
import java.util.stream.Stream
import kotlin.math.roundToInt

data class BolusProgressCallback(
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
        MedLinkStatusParser.parseBolusInfo(ans, medLinkPumpPlugin.medLinkPumpStatus, aapsLogger)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + medLinkPumpPlugin.medLinkPumpStatus.lastBolusAmount)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + medLinkPumpPlugin.medLinkPumpStatus.bolusDeliveredAmount)
        aapsLogger.info(LTag.PUMPBTCOMM, "" + medLinkPumpPlugin.medLinkPumpStatus.lastBolusInfo)
        aapsLogger.info(LTag.PUMPBTCOMM, detailedBolusInfo.toString())
        if (medLinkPumpPlugin.medLinkPumpStatus.lastBolusAmount != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "lastbolusAmount")
            val deliveredAmount: Double = if (medLinkPumpPlugin.medLinkPumpStatus.bolusDeliveredAmount != null) {
                medLinkPumpPlugin.medLinkPumpStatus.bolusDeliveredAmount!!
            } else {
                0.0
            }
            var bolusEvent =
                EventOverviewBolusProgress(resourceHelper, deliveredAmount)


            rxBus.send(bolusEvent)
            if (deliveredAmount == detailedBolusInfo.insulin || medLinkPumpPlugin.medLinkPumpStatus.bolusDeliveredAmount == 0.0) {
                aapsLogger.info(LTag.PUMPBTCOMM, "boluscompleted")

                // medLinkPumpPlugin.handleNewTreatmentData(Stream.of(JSONObject(detailedBolusInfo.toJsonString())))

                medLinkPumpPlugin.medLinkPumpStatus.lastBolusInfo.let {
                    it?.timestamp = medLinkPumpPlugin.medLinkPumpStatus.lastBolusTime?.time ?: it.timestamp

                    it?.insulin = medLinkPumpPlugin.medLinkPumpStatus.lastBolusAmount ?: it.insulin

                    it?.bolusType = detailedBolusInfo.bolusType
                    it?.carbs = detailedBolusInfo.carbs
                    it?.eventType = detailedBolusInfo.eventType
//                    aapsLogger.info(LTag.PUMP, it!!.toJsonString())?
                    medLinkPumpPlugin.handleNewTreatmentData(it!!)
                }

                SystemClock.sleep(200)

                bolusEvent = EventOverviewBolusProgress(resourceHelper, percent = 100)
                rxBus.send(bolusEvent)
                SystemClock.sleep(1000)

                // rxBus.send(bolusEvent)
                rxBus.send(EventDismissBolusProgressIfRunning(null, medLinkPumpPlugin.medLinkPumpStatus.lastBolusTime?.time))
                resend = false
            }
            //else commandExecutor?.clearExecutedCommand()
        }
        return super.apply(answer)
    }

}