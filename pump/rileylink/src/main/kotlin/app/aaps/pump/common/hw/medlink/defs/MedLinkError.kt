package app.aaps.pump.common.hw.medlink.defs

import app.aaps.pump.common.hw.rileylink.R
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice


/**
 * Created by andy on 14/05/2018.
 */
enum class MedLinkError {
    // Configuration
    // BT
    NoBluetoothAdapter(R.string.rileylink_error_no_bt_adapter),  //
    BluetoothDisabled(R.string.rileylink_error_bt_disabled),  //

    // RileyLink
    MedLinkUnreachable(R.string.medlink_error_unreachable),  //
    DeviceIsNotMedLink(R.string.medlink_error_not_rl),  //

    // Device
    TuneUpOfDeviceFailed(R.string.rileylink_error_tuneup_failed),  //
    NoContactWithDevice(
        R.string.rileylink_error_pump_unreachable,
        R.string.rileylink_error_pod_unreachable
    ),
    ; //

    var resourceId: Int
    var resourceIdPod: Int? = null


    constructor(resourceId: Int) {
        this.resourceId = resourceId
    }


    constructor(resourceId: Int, resourceIdPod: Int) {
        this.resourceId = resourceId
        this.resourceIdPod = resourceIdPod
    }


    fun getResourceId(targetDevice: RileyLinkTargetDevice?): Int {
        if (this.resourceIdPod != null) {
            return (if (targetDevice == RileyLinkTargetDevice.MedtronicPump)  //
                this.resourceId
            else
                this.resourceIdPod)!!
        } else {
            return this.resourceId
        }
    }
}
