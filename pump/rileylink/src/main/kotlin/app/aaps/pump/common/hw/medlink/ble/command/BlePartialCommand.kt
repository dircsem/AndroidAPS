package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.core.interfaces.logging.AAPSLogger

open class BlePartialCommand(aapsLogger: AAPSLogger, medLinkServiceData: MedLinkServiceData) :
    BleCommand(aapsLogger, medLinkServiceData) {

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCharacteristic: String) {
        if (answer.contains("time to powerdown") && !pumpResponse.toString().contains("ready")) {
            val resp = pumpResponse.toString()
            pumpResponse = StringBuffer()
            super.applyResponse(resp, bleComm.currentCommand, bleComm)

        } else {
            super.characteristicChanged(answer, bleComm, lastCharacteristic)
        }
    }

}