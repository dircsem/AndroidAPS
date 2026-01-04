package info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.PumpRunningState
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.MedLinkBLE
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkServiceData

/**
 * Created by Dirceu on 24/03/21.
 */
class BleBolusCommand(aapsLogger: AAPSLogger, medLinkServiceData: MedLinkServiceData, val medLinkPumpPluginAbstract: MedLinkPumpPluginBase, val detailedBolusInfo: DetailedBolusInfo) :
    BleActivePumpCommand(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    override fun characteristicChanged(
        answer: String, bleComm: MedLinkBLE,
        lastCharacteristic: String,
    ) {
        aapsLogger.info(LTag.PUMPBTCOMM, answer)
        aapsLogger.info(LTag.PUMPBTCOMM, lastCharacteristic)
        if (bleComm.currentCommand != null && bleComm.isBolus(bleComm.currentCommand!!.getCurrentCommand()) && answer.trim { it <= ' ' }.contains("pump is bolusing")) {
            bleComm.resetBolusCommand()
        } else
            if (answer.contains("pump suspend state")) {
                bleComm.removeFirstCommand(true)
                medLinkPumpPluginAbstract.pumpStatusData.pumpRunningState = PumpRunningState.Suspended
                medLinkPumpPluginAbstract.reDeliverBolus(detailedBolusInfo)
            } else if (answer.trim { it <= ' ' }.contains("set bolus")) {
                aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())
                // bleComm.nextCommand()
                bleComm.completedCommand()
            } else {
                super.characteristicChanged(answer, bleComm, lastCharacteristic)
            }
    }
}