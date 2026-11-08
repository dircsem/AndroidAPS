package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser

/**
 * Created by Dirceu on 24/03/21.
 *
 * T7: this command never re-sends a bolus by itself. Any unclear answer is handed to MedLinkBLE,
 * which verifies the bolus with `M` and lets the BolusTracker decide.
 */
class BleBolusCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData?,
    val medLinkPumpPluginAbstract: MedLinkPumpPluginBase,
    val detailedBolusInfo: DetailedBolusInfo,
    val hasSupplementaryCommands: Boolean
) :
    BleActivePumpCommand(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    /**
     * Result reported by MedLinkBLE when this command's own answer callback will not run:
     * T14 folded into another queued loop bolus (the part of the merged dose recorded for it), or
     * T15 a manual bolus dropped from the queue. Called once; success=false, 0.0 when not delivered.
     */
    var onQueueResult: ((success: Boolean, delivered: Double) -> Unit)? = null

    override fun characteristicChanged(
        answer: String, bleComm: MedLinkBLE,
        lastCharacteristic: String,
    ) {
        val trimmed = answer.trim { it <= ' ' }
        when {
            bleComm.currentCommand != null && bleComm.isBolus(bleComm.currentCommand!!.getCurrentCommand()) && trimmed.contains("pump is bolusing") -> {
                // a bolus is running: whether ours was taken is decided by M once it ends, never by re-sending
                aapsLogger.info(LTag.PUMPBTCOMM, "bolus ${detailedBolusInfo.insulin}U: pump is bolusing, verifying with M")
                bleComm.onBolusUnclear()
            }

            answer.contains("pump suspend state")                                                                                         -> {
                medLinkPumpPluginAbstract.setSuspended()
                if (bleComm.onBolusRejectedBeforeWrite()) {
                    // the dose was never written: safe to deliver again once the pump is started
                    if (hasSupplementaryCommands) bleComm.removeBolusAndSuppCommand() else bleComm.removeFirstCommand(true)
                    medLinkPumpPluginAbstract.reDeliverBolus(detailedBolusInfo)
                } else {
                    aapsLogger.info(LTag.PUMPBTCOMM, "bolus ${detailedBolusInfo.insulin}U: pump suspended after the dose was written, verifying with M")
                    bleComm.onBolusUnclear()
                }
            }

            trimmed.contains("set bolus")                                                                                                 -> {
                aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())
                bleComm.completedCommand()
            }

            trimmed.contains("last bolus")                                                                                                -> {
                pumpResponse.append(answer)
                bleComm.onBolusStatus(MedLinkStatusParser.parseBolusSnapshot(answer.split("\n"), System.currentTimeMillis()))
            }

            else                                                                                                                          ->
                super.characteristicChanged(answer, bleComm, lastCharacteristic)
        }
    }
}
