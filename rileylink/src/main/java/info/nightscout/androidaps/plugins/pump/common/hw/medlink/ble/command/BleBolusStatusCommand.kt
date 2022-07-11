package info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.command

import info.nightscout.androidaps.plugins.pump.common.MedLinkPumpPluginAbstract
import info.nightscout.androidaps.plugins.pump.common.data.MedLinkPartialBolus
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities.BolusProgressCallback
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.MedLinkBLE
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkServiceData
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkStatusParser
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag

class BleBolusStatusCommand(
    aapsLogger: AAPSLogger,
    medLinkServiceData: MedLinkServiceData,
    medLinkPumpPluginAbstract: MedLinkPumpPluginAbstract
) :
    BleCommandReader(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    private var status: MedLinkPartialBolus = MedLinkPartialBolus(PumpType.MEDLINK_MEDTRONIC_554_754_VEO)

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCommand: String) {
        aapsLogger.info(LTag.PUMPBTCOMM, answer)
        if (answer.contains("time to powerdown 5")) {
            bleComm.nextCommand();
        } else if (answer.contains("ready")) {
            pumpResponse.append(answer)
            val fullResponse = pumpResponse.toString()
            val responseIterator = fullResponse.substring(fullResponse.indexOf("last")).split("\n").iterator()
            status = MedLinkStatusParser.parseBolusInfo(
                responseIterator, status
            )
            if(bleComm.needToCheckOnHold && bleComm.onHoldCommandQueue.isNotEmpty()){
                val onHoldCommand = bleComm.onHoldCommandQueue.first
                val firstCommand = onHoldCommand.commandList.first()
                aapsLogger.info(LTag.PUMPBTCOMM,"onholdcheck")
                if(bleComm.isBolus(firstCommand.command) && firstCommand.parseFunction.isPresent &&
                    firstCommand.parseFunction.get() is BolusProgressCallback){
                    aapsLogger.info(LTag.PUMPBTCOMM,"bolusOnHold")
                    val callback: BolusProgressCallback= firstCommand.parseFunction.get() as BolusProgressCallback
                    if(callback.detailedBolusInfo.insulin == status.lastBolusAmount){
                        aapsLogger.info(LTag.PUMPBTCOMM,"remove old bolus")
                        bleComm.onHoldCommandQueue.removeFirst()
                        bleComm.needToCheckOnHold = bleComm.onHoldCommandQueue.isNotEmpty()
                    }
                }
            }
            aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())
            aapsLogger.info(LTag.PUMPBTCOMM, status.toString())
            if (status.lastBolusAmount != null && status.bolusDeliveredAmount > 0 &&
                status.bolusDeliveredAmount < status.lastBolusAmount!!
            ) {
                bleComm.clearExecutedCommand()
            } else {
                applyResponse(pumpResponse.toString(), bleComm.currentCommand, bleComm)
                bleComm.completedCommand()
            }
            pumpResponse = StringBuffer()
        } else if (!(lastCommand+answer).contains("time to powerdown") || answer.contains("confirmed")) {
            super.characteristicChanged(answer, bleComm, lastCommand)
        }
    }
}