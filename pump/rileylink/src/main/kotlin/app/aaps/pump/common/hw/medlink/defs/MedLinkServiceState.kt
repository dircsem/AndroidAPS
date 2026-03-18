package app.aaps.pump.common.hw.medlink.defs

import app.aaps.pump.common.hw.rileylink.R


/**
 * Created by andy on 14/05/2018.
 */
enum class MedLinkServiceState(@JvmField val resourceId: Int) {
    NotStarted(R.string.rileylink_state_not_started),  //

    // Bluetooth
    BluetoothInitializing(R.string.rileylink_state_bt_init),  // (S) init BT (if error no BT interface -> Disabled, BT

    // not enabled -> BluetoothError)
    // BluetoothNotAvailable, // (E) BT not available, would happen only if device has no BT
    BluetoothError(R.string.rileylink_state_bt_error),  // (E) if BT gets disabled ( -> EnableBluetooth)
    BluetoothReady(R.string.rileylink_state_bt_ready),  // (OK)

    // RileyLink
    MedLinkInitializing(R.string.medlink_state_rl_init),  // (S) start Gatt discovery (OK -> RileyLinkReady, Error ->

    // BluetoothEnabled) ??
    MedLinkError(R.string.medlink_state_rl_error),  // (E)
    MedLinkReady(R.string.medlink_state_rl_ready),  // (OK) if tunning was already done we go to PumpConnectorReady

    // Tunning
    TuneUpDevice(R.string.medlink_state_pc_tune_up),  // (S)
    PumpConnectorError(R.string.rileylink_state_pc_error),  // either TuneUp Error or pump couldn't not be contacted

    // error
    PumpConnectorReady(R.string.rileylink_state_connected),
    ; // (OK) RileyLink Ready for Pump Communication
    // Initializing, // get all parameters required for connection (if not possible -> Disabled, if sucessful ->
    // EnableBluetooth)
    // EnableBlueTooth, // enable BT (if error no BT interface -> Disabled, BT not enabled -> BluetoothError)
    // BlueToothEnabled, // -> InitializeRileyLink
    // RileyLinkInitialized, //
    // RileyLinkConnected, // -> TuneUpPump (on 1st), else PumpConnectorReady
    // PumpConnected, //

    val isReady: Boolean
        get() = (this == PumpConnectorReady)

    val isConnecting: Boolean
        get() = (this == BluetoothInitializing ||  //
                // this == RileyLinkServiceState.BluetoothError || //
                this == BluetoothReady ||  //
                this == MedLinkInitializing ||  //
                this == MedLinkReady // this == RileyLinkServiceState.RileyLinkBLEError
                )

    val isError: Boolean
        get() = (this == BluetoothError ||  //
                // this == RileyLinkServiceState.PumpConnectorError || //
                this == MedLinkError)
}
