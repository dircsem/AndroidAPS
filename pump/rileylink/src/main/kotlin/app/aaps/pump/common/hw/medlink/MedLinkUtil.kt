package app.aaps.pump.common.hw.medlink

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.aaps.pump.common.hw.medlink.data.MLHistoryItem
import app.aaps.pump.common.hw.medlink.defs.MedLinkEncodingType
import app.aaps.pump.common.hw.rileylink.ble.data.encoding.Encoding4b6b
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by dirceu on 9/17/20.
 * copied from RileyLinkUtil
 */
@Singleton
class MedLinkUtil @Inject constructor() {
    //    @Override public void finishCurrentTask(ServiceTask task) {
    //        if (task != currentTask) {
    //            //LOG.error("finishCurrentTask: task does not match");
    //        }
    //        // hack to force deep copy of transport contents
    //        ServiceTransport transport = task.getServiceTransport().clone();
    //
    //        if (transport.hasServiceResult()) {
    //            sendServiceTransportResponse(transport, transport.getServiceResult());
    //        }
    //        currentTask = null;
    //    }
    //    private static void sendServiceTransportResponse(ServiceTransport transport, ServiceResult serviceResult) {
    //        // get the key (hashcode) of the client who requested this
    //        Integer clientHashcode = transport.getSenderHashcode();
    //        // make a new bundle to send as the message data
    //        transport.setServiceResult(serviceResult);
    //        // FIXME
    //        // transport.setTransportType(RT2Const.IPC.MSG_ServiceResult);
    //        // rileyLinkIPCConnection.sendTransport(transport, clientHashcode);
    //    }
    val historyMedLink: MutableList<MLHistoryItem?> = ArrayList<MLHistoryItem?>()
        //    @Override public void finishCurrentTask(ServiceTask task) {
        get() {
            return field
        }
    private var currentTask: ServiceTask? = null

    var encoding: MedLinkEncodingType = MedLinkEncodingType.FourByteSixByteLocal
        set(encoding) {
            this.encoding = encoding
        }
    val encoding4b6b: Encoding4b6b? = null

    // TODO maybe not needed
    private var rileyLinkTargetFrequency: RileyLinkTargetFrequency? = null

    fun sendBroadcastMessage(message: Intent, context: Context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(message)
    }

    fun sendBroadcastMessage(message: String?, context: Context) {
        val intent = Intent(message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    // FIXME remove ?
    fun setCurrentTask(task: ServiceTask?) {
        if (currentTask == null) {
            currentTask = task
        } else {
            //LOG.error("setCurrentTask: Cannot replace current task");
        }
    }

    //    @Override public void finishCurrentTask(ServiceTask task) {
    //        if (task != currentTask) {
    //            //LOG.error("finishCurrentTask: task does not match");
    //        }
    //        // hack to force deep copy of transport contents
    //        ServiceTransport transport = task.getServiceTransport().clone();
    //
    //        if (transport.hasServiceResult()) {
    //            sendServiceTransportResponse(transport, transport.getServiceResult());
    //        }
    //        currentTask = null;
    //    }
    //    private static void sendServiceTransportResponse(ServiceTransport transport, ServiceResult serviceResult) {
    //        // get the key (hashcode) of the client who requested this
    //        Integer clientHashcode = transport.getSenderHashcode();
    //        // make a new bundle to send as the message data
    //        transport.setServiceResult(serviceResult);
    //        // FIXME
    //        // transport.setTransportType(RT2Const.IPC.MSG_ServiceResult);
    //        // rileyLinkIPCConnection.sendTransport(transport, clientHashcode);
    //    }
    fun getRileyLinkHistory(): MutableList<MLHistoryItem?> {
        return historyMedLink
    }

    fun getMedLinkHistory(): MutableList<MLHistoryItem?> {
        return historyMedLink
    }

    fun setRileyLinkTargetFrequency(rileyLinkTargetFrequency_: RileyLinkTargetFrequency?) {
        this.rileyLinkTargetFrequency = rileyLinkTargetFrequency_
    }
}

