package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType

import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.queue.Callback
import app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpAnswerState
import app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpStateAnswer
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState

class BleStartCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData?,
    val medLinkPumpPluginAbstract: MedLinkPumpPluginBase,
) :
    BleStartStopCommand(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    // private var checkingStatus: Boolean = false

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCharacteristic: String) {
        // if (answer!!.contains("check pump status")) {
        //     checkingStatus = true
        // } else
        aapsLogger.info(LTag.PUMPBTCOMM, answer)
        aapsLogger.info(LTag.PUMPBTCOMM, lastCharacteristic)
        when {
            MedLinkPumpStateAnswer.isBolusing(answer) ||
                answer.contains("pump normal state") -> {
                aapsLogger.info(LTag.PUMPBTCOMM, "status command")
                aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())
                pumpResponse.append(answer)
                // T2: a bolusing pump is not suspended, but it is not "Running" either
                if (MedLinkPumpStateAnswer.isBolusing(answer)) {
                    bleComm.setLastPumpStatus(MedLinkPumpRunningState.Bolusing)
                    bleComm.onPumpAnswerState(MedLinkPumpAnswerState.BOLUSING)
                } else {
                    bleComm.setLastPumpStatus(MedLinkPumpRunningState.Running)
                    bleComm.onPumpAnswerState(MedLinkPumpAnswerState.RUNNING)
                }

                if (bleComm.currentCommand?.nextCommand() == MedLinkCommandType.NoCommand || bleComm.currentCommand?.nextCommand() == MedLinkCommandType.StartPump) {
                    applyResponse(pumpResponse.toString(), bleComm.currentCommand, bleComm)
                    if (bleComm.currentCommand?.nextCommand() == MedLinkCommandType.StartPump) {
                        bleComm.currentCommand?.commandExecuted()
                    }
                }
                medLinkPumpPluginAbstract.cancelTempBasal(true, true, object : Callback() {
                    override fun run() {
                        aapsLogger.info(LTag.PUMPBTCOMM, "tbr cancelled")
                    }
                })
                pumpResponse = StringBuffer()
                bleComm.completedCommand(force = true, waitNextIteration = true)
            }

            answer.contains("pump suspend state")    -> {
                bleComm.setLastPumpStatus(MedLinkPumpRunningState.Suspended)
                bleComm.onPumpAnswerState(MedLinkPumpAnswerState.SUSPENDED)
                if (bleComm.currentCommand?.nextCommand() == MedLinkCommandType.StartPump) {
                    // answer to A: the pump is suspended as expected, write START now
                    bleComm.completedCommand()
                } else {
                    // T13 / bug 4: START was written and the pump is still suspended - retry and alarm, never drop silently
                    aapsLogger.warn(LTag.PUMPBTCOMM, "START refused: pump still suspended")
                    bleComm.onStartFailed("pump still suspended after START")
                }
            } else                                     -> {
                super.characteristicChanged(answer, bleComm, lastCharacteristic)
            }
        }
    }
}