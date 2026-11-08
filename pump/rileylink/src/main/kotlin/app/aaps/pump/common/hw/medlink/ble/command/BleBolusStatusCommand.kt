package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser

/**
 * `M` (BolusStatus) answer handler.
 *
 * T5: the answer is parsed strictly and handed to MedLinkBLE / BolusTracker, which alone decides
 * whether a held bolus was delivered, is still delivering, or may be re-sent.
 */
class BleBolusStatusCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData?,
    val medLinkPumpPluginAbstract: MedLinkPumpPluginBase
) :
    BleCommandReader(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCharacteristic: String) {
        val current = bleComm.currentCommand
        if (answer.contains("time to powerdown 5") && current != null && !current.hasFinished() && current.isAwaitingAnswer()) {
            // MedLink is ending the session and the M answer never came: read M again from the start
            // (M only reads, it never delivers). Posting it as-is would write an empty payload -> "invalid command".
            aapsLogger.info(LTag.PUMPBTCOMM, "M answer lost, reading M again")
            pumpResponse = StringBuffer()
            bleComm.clearExecutedCommand()
            bleComm.nextCommand()
        } else if (current?.hasFinished() != false || answer.contains("time to powerdown 5")) {
            bleComm.nextCommand()
        } else if (answer.contains("ready")) {
            pumpResponse.append(answer)
            val fullResponse = pumpResponse.toString()
            var inProgress = false
            if (fullResponse.contains("last")) {
                val lines = fullResponse.substring(fullResponse.indexOf("last")).split("\n")
                val snapshot = MedLinkStatusParser.parseBolusSnapshot(lines, System.currentTimeMillis())
                aapsLogger.info(LTag.PUMPBTCOMM, "M answer: $snapshot")
                snapshot?.let { updatePumpStatus(it.lastBolusAmount, it.lastBolusTime, it.deliveredAmount) }
                bleComm.onBolusStatus(snapshot)
                inProgress = snapshot?.inProgress == true
            }
            aapsLogger.info(LTag.PUMPBTCOMM, fullResponse)
            if (inProgress) {
                // a bolus is still being delivered: read M again (this re-reads status, it never re-sends a bolus)
                bleComm.clearExecutedCommand()
            } else {
                bleComm.currentCommand?.let { applyResponse(fullResponse, it, bleComm) }
                bleComm.completedCommand()
            }
            pumpResponse = StringBuffer()
        } else if (!(lastCharacteristic + answer).contains("time to powerdown") || answer.contains("confirmed")) {
            super.characteristicChanged(answer, bleComm, lastCharacteristic)
        }
    }

    private fun updatePumpStatus(lastBolusAmount: Double, lastBolusTime: Long, delivered: Double?) {
        val status = medLinkPumpPluginAbstract.medLinkPumpStatus
        status.lastBolusAmount = lastBolusAmount
        status.lastBolusTime = java.util.Date(lastBolusTime)
        status.bolusDeliveredAmount = delivered ?: 0.0
    }
}
