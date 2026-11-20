package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData

/**
 * Created by Dirceu on 24/03/21.
 *
 * T7: this command never re-sends a bolus by itself. Any unclear answer is handed to MedLinkBLE,
 * which verifies the bolus with `M` and lets the MedLinkBolusTracker decide.
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
     * The result of this bolus, reported by MedLinkBLE once the tracker resolved it (delivered / partial
     * recorded from the bolus answer or `M`, failed, dropped), also when it was merged into another bolus.
     * May be called more than once: the owner keeps the first call. success=false, 0.0 when not delivered.
     */
    var onQueueResult: ((success: Boolean, delivered: Double) -> Unit)? = null

    /**
     * This bolus was joined with other boluses: the pump gets one bolus with this total (for example manual 3.0 U +
     * TBR 0.2 U = 3.2 U). Used by the progress dialog to show the real amount (field 2026-11-19 23:01).
     */
    var onTotal: ((total: Double) -> Unit)? = null

    /** While the pump delivers this bolus: (delivered so far, total requested). Used by the progress dialog. */
    var onProgress: ((delivered: Double, requested: Double) -> Unit)? = null

    /** 4p: before the pump delivers: what the bolus waits for (try = 1-based try for SENDING, else 0). */
    var onPhase: ((phase: app.aaps.pump.common.hw.medlink.bolus.MedLinkBolusPhase, attempt: Int, maxAttempts: Int) -> Unit)? = null

    /** T16: manual bolus only: the user confirmed it while an SMB was pending, so the SMB may be merged into it. */
    var smbConsent: Boolean = false

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

            else                                                                                                                          -> {
                // the answer ends with "ready": the whole text (chunks joined) tells the outcome of the bolus
                if ((lastCharacteristic + answer).contains("ready", ignoreCase = true) && pumpResponse.toString().contains("last bolus", ignoreCase = true))
                    bleComm.onBolusAnswer(pumpResponse.toString() + answer)
                super.characteristicChanged(answer, bleComm, lastCharacteristic)
            }
        }
    }
}
