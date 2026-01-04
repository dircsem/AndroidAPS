package info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.command

import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.MedLinkBLE
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.defs.MedLinkCommandType

import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.queue.Callback

class BleStartCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData,
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
            answer.contains("pump is bolusing st") ||
                answer.contains("pump normal state") -> {
                aapsLogger.info(LTag.PUMPBTCOMM, "status command")
                aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())

                pumpResponse.append(answer)
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
                bleComm.completedCommand()
            }

            else                                     -> {
                super.characteristicChanged(answer, bleComm, lastCharacteristic)
            }
        }
    }
}