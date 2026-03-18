package app.aaps.pump.common.hw.medlink.data

import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.common.hw.medlink.defs.MedLinkError
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import org.joda.time.LocalDateTime

/**
 * Created by dirceu on 9/25/20.
 * copied from HLHistoryItem
 */
open class MLHistoryItem {

    //    public RLHistoryItem(MedtronicCommandType medtronicCommandType) {
    //        this.dateTime = new LocalDateTime();
    //        this.medtronicCommandType = medtronicCommandType;
    //        source = RLHistoryItemSource.MedtronicCommand;
    //    }
    //
    //
    //    public RLHistoryItem(OmnipodCommandType omnipodCommandType) {
    //        this.dateTime = new LocalDateTime();
    //        this.omnipodCommandType = omnipodCommandType;
    //        source = RLHistoryItemSource.OmnipodCommand;
    //    }
    //private MedtronicCommandType medtronicCommandType;
    protected var historyDateTime: LocalDateTime
    @JvmField protected var source: MLHistoryItemSource
    @JvmField protected var serviceState: MedLinkServiceState? = null
    @JvmField protected var errorCode: MedLinkError? = null
    protected var targetDevice: RileyLinkTargetDevice
    protected var pumpDeviceState: PumpDeviceState? = null

    //private OmnipodCommandType omnipodCommandType;
    constructor(dateTime: LocalDateTime, source: MLHistoryItemSource, targetDevice: RileyLinkTargetDevice) {
        this.historyDateTime = dateTime
        this.source = source
        this.targetDevice = targetDevice
    }

    constructor(
        serviceState: MedLinkServiceState?, errorCode: MedLinkError?,
        targetDevice: RileyLinkTargetDevice
    ) {
        this.targetDevice = targetDevice
        historyDateTime = LocalDateTime()
        this.serviceState = serviceState
        this.errorCode = errorCode
        source = MLHistoryItemSource.MedLink
    }

    constructor(pumpDeviceState: PumpDeviceState?, targetDevice: RileyLinkTargetDevice) {
        this.pumpDeviceState = pumpDeviceState
        historyDateTime = LocalDateTime()
        this.targetDevice = targetDevice
        source = MLHistoryItemSource.MedtronicPump
    }

    //    public RLHistoryItem(MedtronicCommandType medtronicCommandType) {
    //        this.dateTime = new LocalDateTime();
    //        this.medtronicCommandType = medtronicCommandType;
    //        source = RLHistoryItemSource.MedtronicCommand;
    //    }
    //
    //
    //    public RLHistoryItem(OmnipodCommandType omnipodCommandType) {
    //        this.dateTime = new LocalDateTime();
    //        this.omnipodCommandType = omnipodCommandType;
    //        source = RLHistoryItemSource.OmnipodCommand;
    //    }
//    fun getDateTime(): LocalDateTime {
//        return historyDateTime
//    }

//    fun getServiceState(): MedLinkServiceState? {
//        return serviceState
//    }

//    fun getErrorCode(): MedLinkError? {
//        return errorCode
//    }

    open fun getDescription(resourceHelper: ResourceHelper): String? {
        return when (source) {
            MLHistoryItemSource.MedLink       -> ("State: " + resourceHelper.gs(serviceState!!.resourceId)
                + if (errorCode == null) "" else ", Error Code: $errorCode")

            MLHistoryItemSource.MedtronicPump -> resourceHelper.gs(serviceState!!.resourceId)
            else                              -> "Unknown Description"
        }
    }

    fun getSource(): MLHistoryItemSource {
        return source
    }

//    fun getPumpDeviceState(): PumpDeviceState {
//        return pumpDeviceState
//    }

    enum class MLHistoryItemSource(val desc: String) {
        MedLink("MedLink"),

        //
        MedtronicPump("Medtronic"),

        //
        MedtronicCommand("Medtronic")

    }

    class Comparator : java.util.Comparator<MLHistoryItem> {

        override fun compare(o1: MLHistoryItem, o2: MLHistoryItem): Int {
            return o2.historyDateTime.compareTo(o1.historyDateTime)
        }
    }
}
