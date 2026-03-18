package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.pump.common.hw.medlink.activities.BolusProgressCallback
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.pump.common.hw.medlink.data.MedLinkPartialBolus

class BleBolusStatusCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData?,
    val medLinkPumpPluginAbstract: MedLinkPumpPluginBase
) :
    BleCommandReader(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

private var status: MedLinkPartialBolus = MedLinkPartialBolus()

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCharacteristic: String) {
        aapsLogger.info(LTag.PUMPBTCOMM, answer)
        var pumpStatus = medLinkPumpPluginAbstract.medLinkPumpStatus
        if(bleComm.currentCommand!!.hasFinished() || answer.contains("time to powerdown 5")) {
            bleComm.nextCommand()
        } else if (answer.contains("ready")) {
            pumpResponse.append(answer)
            val fullResponse = pumpResponse.toString()
            if(fullResponse.contains("last")) {
                val responseIterator = fullResponse.substring(fullResponse.indexOf("last")).split("\n").iterator()
                val bolusStatus = MedLinkStatusParser.parseBolusInfo(
                    responseIterator, status, aapsLogger
                )
                pumpStatus = convertToPumpStatus(bolusStatus)
                if (bleComm.needToCheckOnHold && bleComm.onHoldCommandQueue.isNotEmpty()) {
                    val onHoldCommand = bleComm.onHoldCommandQueue.first
                    val firstCommand = onHoldCommand.commandList.first()
                    aapsLogger.info(LTag.PUMPBTCOMM, "onholdcheck")
                    aapsLogger.info(LTag.PUMPBTCOMM, firstCommand.toString())
                    if (firstCommand.command.isSameCommand(MedLinkCommandType.BolusStatus) && firstCommand.parseFunction.isPresent &&
                        firstCommand.parseFunction.get() is BolusProgressCallback
                    ) {
                        aapsLogger.info(LTag.PUMPBTCOMM, "bolusOnHold")
                        val callback: BolusProgressCallback = firstCommand.parseFunction.get() as BolusProgressCallback
                        if (callback.detailedBolusInfo.insulin == status.lastBolusAmount) {
                            aapsLogger.info(LTag.PUMPBTCOMM, "remove old bolus")
                            bleComm.onHoldCommandQueue.removeFirst()
                            bleComm.needToCheckOnHold = bleComm.onHoldCommandQueue.isNotEmpty()
                        } else {
                            aapsLogger.info(LTag.PUMPBTCOMM, "reprocess command")
                            bleComm.reprocessOnHold()
                        }
                    }
                }
            }
            aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())
            aapsLogger.info(LTag.PUMPBTCOMM, status.toString())
            if (pumpStatus.bolusDeliveredAmount!! > 0 &&
                pumpStatus.bolusDeliveredAmount!! < (pumpStatus.lastBolusAmount ?: 0.0)
            ) {
                bleComm.clearExecutedCommand()
            } else {
                applyResponse(pumpResponse.toString(), bleComm.currentCommand, bleComm)
                bleComm.completedCommand()
            }
            pumpResponse = StringBuffer()
        } else if (!(lastCharacteristic+answer).contains("time to powerdown") || answer.contains("confirmed")) {
            super.characteristicChanged(answer, bleComm, lastCharacteristic)
        }
    }

    private fun convertToPumpStatus(bolusStatus: MedLinkPartialBolus): MedLinkPumpStatus {
        val status = medLinkPumpPluginAbstract.medLinkPumpStatus
        status.bolusDeliveredAmount = bolusStatus.bolusDeliveredAmount
        status.lastBolusTime = bolusStatus.lastBolusTime
        return status
    }
}