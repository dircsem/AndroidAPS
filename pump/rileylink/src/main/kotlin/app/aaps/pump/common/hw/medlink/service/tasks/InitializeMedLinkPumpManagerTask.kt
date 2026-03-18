package app.aaps.pump.common.hw.medlink.service.tasks

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpDevice
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTask
import javax.inject.Inject

/**
 * Created by geoff on 7/9/16.
 *
 *
 * This class is intended to be run by the Service, for the Service. Not intended for clients to run.
 */
class InitializeMedLinkPumpManagerTask@Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val medLinkServiceData: MedLinkServiceData,
    activePlugin: ActivePlugin) : ServiceTask(activePlugin ) {



    override fun run() {
        var lastGoodFrequency: Double

//        if (medLinkServiceData.lastGoodFrequency == null) {
//
//            lastGoodFrequency = sp.getDouble(RileyLinkConst.Prefs.LastGoodDeviceFrequency, 0.0d);
//            lastGoodFrequency = Math.round(lastGoodFrequency * 1000d) / 1000d;
//
//            medLinkServiceData.lastGoodFrequency = lastGoodFrequency;
//
////            if (RileyLinkUtil.getRileyLinkTargetFrequency() == null) {
////                String pumpFrequency = SP.getString(MedtronicConst.Prefs.PumpFrequency, null);
////            }
//        } else {
//            lastGoodFrequency = medLinkServiceData.lastGoodFrequency;
//        }
        val medLinkCommunicationManager = (activePlugin.activePump as MedLinkPumpDevice).medLinkService?.deviceCommunicationManager
        aapsLogger.info(LTag.PUMPBTCOMM, activePlugin.activePump.manufacturer().description)
        if (activePlugin.activePump.manufacturer() === ManufacturerType.Medtronic) {

//            if ((lastGoodFrequency > 0.0d)
//                    && medLinkCommunicationManager.isValidFrequency(lastGoodFrequency)) {
            medLinkServiceData.medLinkServiceState = MedLinkServiceState.MedLinkReady

//                aapsLogger.info(LTag.PUMPBTCOMM, "Setting radio frequency to {} MHz", lastGoodFrequency);

//                medLinkCommunicationManager.setRadioFrequencyForPump(lastGoodFrequency);

//                boolean foundThePump =
//            medLinkCommunicationManager.getPumpStatus().lastConnection;
            if (!activePlugin.activePump.isInitialized() || medLinkCommunicationManager?.pumpStatus!!.lastDataTime > 0L && System.currentTimeMillis() -
                medLinkCommunicationManager.pumpStatus!!.lastConnection > 300000
            ) {
                medLinkCommunicationManager?.wakeUp(false)
            }

//                if (foundThePump) {
//                    medLinkServiceData.setRileyLinkServiceState(RileyLinkServiceState.PumpConnectorReady);
//                } else {
//                    medLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorError,
//                            RileyLinkError.NoContactWithDevice);
//                    medLinkUtil.sendBroadcastMessage(RileyLinkConst.IPC.MSG_PUMP_tunePump, context);
//                }

//            } else {
//                medLinkUtil.sendBroadcastMessage(RileyLinkConst.IPC.MSG_PUMP_tunePump, context);
//            }
        } else {

//            if (!Round.isSame(lastGoodFrequency, RileyLinkTargetFrequency.Omnipod.getScanFrequencies()[0])) {
//                lastGoodFrequency = RileyLinkTargetFrequency.Omnipod.getScanFrequencies()[0];
//                lastGoodFrequency = Math.round(lastGoodFrequency * 1000d) / 1000d;
//
//                medLinkServiceData.lastGoodFrequency = lastGoodFrequency;
//            }

//            medLinkServiceData.setRileyLinkServiceState(RileyLinkServiceState.RileyLinkReady);
//            medLinkServiceData.rileyLinkTargetFrequency = RileyLinkTargetFrequency.Omnipod; // TODO shouldn't be needed

//            aapsLogger.info(LTag.PUMPBTCOMM, "Setting radio frequency to {} MHz", lastGoodFrequency);

//            medLinkCommunicationManager.setRadioFrequencyForPump(lastGoodFrequency);
            medLinkServiceData.medLinkServiceState = MedLinkServiceState.PumpConnectorReady
        }
    }
}