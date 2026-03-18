package app.aaps.pump.common.hw.medlink

import app.aaps.pump.common.hw.rileylink.R
import java.util.Collections


/**
 * Created by dirceu ou 18/09/20
 * Copied from RileyLinkConst
 */
object MedLinkConst {
    const val Prefix: String = "AAPS.MedLink."
    var FREQUENCY_CALIBRATION_SUCCESS: String = "medlink calibration success"
    var DEVICE_MAC_ADDRESS: String? = null
    val DEVICE_NAME: MutableList<String?> = Collections.unmodifiableList<String?>(
        object : ArrayList<String?>() {
            init {
                add("MEDLINK")
                add("MED-LINK")
                add("MED-LINK-2")
                add("MED-LINK-3")
                add("HMSoft")
            }
        })

    object Intents {
        val MedLinkReady: String = Prefix + "MedLink_Ready"
        val MedLinkReadyServiceDiscovered: String = Prefix + "MedLink_ServiceDiscovered"
        val CommandCompleted: String = Prefix + "Command_Completed"
        val RileyLinkGattFailed: String = Prefix + "RileyLink_Gatt_Failed"
        val MedLinkConnected: String = Prefix + "MedLink_Connected"
        val BluetoothConnected: String = Prefix + "Bluetooth_Connected"
        val BluetoothReconnected: String = Prefix + "Bluetooth_Reconnected"
        val BluetoothDisconnected: String = Prefix + "Bluetooth_Disconnected"
        val MedLinkDisconnected: String = Prefix + "MedLink_Disconnected"

        val MedLinkNewAddressSet: String = Prefix + "NewAddressSet"

        val INTENT_NEW_rileylinkAddressKey: String = Prefix + "INTENT_NEW_rileylinkAddressKey"
        val INTENT_NEW_pumpIDKey: String = Prefix + "INTENT_NEW_pumpIDKey"
        val MedLinkDisconnect: String = Prefix + "MedLink_Disconnect"

        val MedLinkConnectionError: String = Prefix + "MedLink_Disconnect"
    }

    object Prefs {
        //public static final String PrefPrefix = "pref_rileylink_";
        //public static final String RileyLinkAddress = PrefPrefix + "mac_address"; // pref_rileylink_mac_address
        val MedLinkAddress: Int = R.string.key_medlink_mac_address
        val MedLinkName: Int = R.string.key_medlink_name

        val LastGoodDeviceCommunicationTime: String = Prefix + "lastGoodDeviceCommunicationTime"
        val LastGoodDeviceFrequency: String = Prefix + "LastGoodDeviceFrequency"
        val Encoding: Int = R.string.key_medtronic_pump_encoding_medlink
    }

    object IPC {
        // needs to br renamed (and maybe removed)
        val MSG_PUMP_quickTune: String = Prefix + "MSG_PUMP_quickTune"
        val MSG_PUMP_tunePump: String = Prefix + "MSG_PUMP_tunePump"

        val MSG_ServiceCommand: String = Prefix + "MSG_ServiceCommand"
    }
}
