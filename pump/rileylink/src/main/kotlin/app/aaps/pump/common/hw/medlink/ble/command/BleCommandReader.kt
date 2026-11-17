package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.pump.common.hw.medlink.keys.MedLinkLongKey

open class BleCommandReader(
    aapsLogger: AAPSLogger, medLinkServiceData: MedLinkServiceData?,
    private val medLinkPumpPluginAbstract: MedLinkPumpPluginBase?
) : BleCommand(aapsLogger, medLinkServiceData) {

    override fun characteristicChanged(answer: String, bleComm: MedLinkBLE, lastCharacteristic: String) {
        if (!lastCharacteristic.contains("ready") && !lastCharacteristic.contains("eomeomeom") &&
            (lastCharacteristic + answer).contains("ready") ||
            !lastCharacteristic.contains("eomeomeom") && answer.contains("eomeomeom")
        ) {
            answer.let { aapsLogger.info(LTag.PUMPBTCOMM, it) }
            rememberLastGoodDeviceCommunicationTime()
            medLinkPumpPluginAbstract?.serviceClass
            // medLinkPumpPluginAbstract?.setPumpDeviceState(PumpDeviceState.Active)
            //TODO verifry impact
            if (medLinkPumpPluginAbstract != null && !medLinkPumpPluginAbstract.isInitialized()) {
                medLinkPumpPluginAbstract.postInit()
            }
        }

        // B16: only a NEW banner. The banner stays the previous chunk when the next answer comes; seeing it again set
        // "not connected" and a second OK+CONN was written inside a STOP / START ("invalid command", field 2026-11-17).
        if (!lastCharacteristic.contains("medtronic") && (lastCharacteristic + answer).contains("medtronic")) {
            bleComm.setIsConnected(false)
            if ((lastCharacteristic + answer).contains("veo")) {
                //TODO need to get better model information
                medLinkPumpPluginAbstract?.setMedtronicPumpModel("754")
            } else {
                medLinkPumpPluginAbstract?.setMedtronicPumpModel("722")
            }
        }
        super.characteristicChanged(answer, bleComm, lastCharacteristic)
    }

    private fun rememberLastGoodDeviceCommunicationTime() {
        medLinkPumpPluginAbstract?.preferences?.get(MedLinkLongKey.LastGoodDeviceCommunicationTime)
        medLinkPumpPluginAbstract?.setLastCommunicationToNow()
    }
}
