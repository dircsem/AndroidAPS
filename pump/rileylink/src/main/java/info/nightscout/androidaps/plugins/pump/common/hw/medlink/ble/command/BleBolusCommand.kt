package info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.command

import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.MedLinkBLE
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkServiceData

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase

/**
 * Created by Dirceu on 24/03/21.
 */
class BleBolusCommand(aapsLogger: AAPSLogger, medLinkServiceData: MedLinkServiceData, medLinkPumpPluginAbstract: MedLinkPumpPluginBase) :
    BleActivePumpCommand(aapsLogger, medLinkServiceData, medLinkPumpPluginAbstract) {

    override fun characteristicChanged(
        answer: String, bleComm: MedLinkBLE,
        lastCharacteristic: String,
    ) {
        aapsLogger.info(LTag.PUMPBTCOMM, answer)
        aapsLogger.info(LTag.PUMPBTCOMM, lastCharacteristic)
        if (answer.trim { it <= ' ' }.contains("set bolus")) {
            aapsLogger.info(LTag.PUMPBTCOMM, pumpResponse.toString())
            bleComm.completedCommand()
        } else {
            super.characteristicChanged(answer, bleComm, lastCharacteristic)
        }
    }
}