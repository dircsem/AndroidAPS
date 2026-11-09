package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.MedLinkStopOrigin
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpAnswerState
import app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpStateAnswer
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData

class BleStopCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData?,
    medLinkPumpPluginAbstract: MedLinkPumpPluginBase,
    val origin: MedLinkStopOrigin = MedLinkStopOrigin.AUTOMATIC
) : BleStartStopCommand(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCharacteristic: String) {
        aapsLogger.info(LTag.PUMPBTCOMM, answer)
        aapsLogger.info(LTag.PUMPBTCOMM, lastCharacteristic)
        when {
            answer.contains("pump suspend state")  -> {
                aapsLogger.info(LTag.PUMPBTCOMM, "stop[$origin] pump suspended")
                pumpResponse.append(answer)
                bleComm.currentCommand?.let { super.applyResponse(pumpResponse.toString(), it, bleComm) }
                pumpResponse = StringBuffer()
                bleComm.completedCommand(true, waitNextIteration = true)
                bleComm.setLastPumpStatus(MedLinkPumpRunningState.Suspended)
                // T3: if a bolus was running the stop aborted it; the tracker reads M to record the partial amount
                bleComm.onPumpAnswerState(MedLinkPumpAnswerState.SUSPENDED)
            }

            answer.contains("pump normal state")   -> {
                bleComm.setLastPumpStatus(MedLinkPumpRunningState.Running)
                bleComm.onPumpAnswerState(MedLinkPumpAnswerState.RUNNING)
                val current = bleComm.currentCommand
                if (current != null && current.secondCommand() == MedLinkCommandType.StopPump && current.nextCommand() == MedLinkCommandType.NoCommand) {
                    // STOP was written and confirmed and the pump still runs: retry, and count it as lock evidence
                    aapsLogger.warn(LTag.PUMPBTCOMM, "STOP refused: pump still running")
                    bleComm.onStopFailed("pump still running after STOP")
                } else {
                    // answer to A: the pump runs as expected, write STOP now
                    bleComm.completedCommand()
                }
            }

            MedLinkPumpStateAnswer.isBolusing(answer)     -> {
                // T2: bolusing is not suspended
                bleComm.setLastPumpStatus(MedLinkPumpRunningState.Bolusing)
                bleComm.onPumpAnswerState(MedLinkPumpAnswerState.BOLUSING)
                if (origin == MedLinkStopOrigin.USER) {
                    // user suspend: send STOP now, aborting the bolus on purpose
                    aapsLogger.info(LTag.PUMPBTCOMM, "stop[USER] pump bolusing, stopping anyway")
                    bleComm.completedCommand()
                } else {
                    // T1: an automatic stop never interrupts a bolus; held without sleeping on the BLE thread (T11)
                    aapsLogger.info(LTag.PUMPBTCOMM, "stop[AUTOMATIC] pump bolusing, holding stop")
                    bleComm.holdStop()
                }
            }

            else                                   -> super.characteristicChanged(answer, bleComm, lastCharacteristic)
        }
    }
}
