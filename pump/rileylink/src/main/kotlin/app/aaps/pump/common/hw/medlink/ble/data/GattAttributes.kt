package app.aaps.pump.common.hw.medlink.ble.data

import java.util.UUID

/**
 * Created by Dirceu on 11/10/20.
 */
object GattAttributes {
    var SERVICE_UUID: String = "0000ffe0-0000-1000-8000-00805f9b34fb"
    var GATT_UUID: String = "0000ffe1-0000-1000-8000-00805f9b34fb"

    //Riley didn't needed
    var MED_LINK_SERVICE_RADIO: String = "0000ffe0-0000-1000-8000-00805f9b34fb"

    //Riley didn't needed
    var CHARA_RADIO_VERSION: String = "30d99dc9-7c91-4295-a051-0a104d238cf2"

    private val attributes: MutableMap<String?, String?>
    private val attributesRileyLinkSpecific: MutableMap<String?, String?>

    // table of names for uuids
    init {
        attributes = HashMap<String?, String?>()

        attributes.put(SERVICE_UUID, "Device Information Service")
        //        attributes.put(CHARA_GAP_NAME, "Name"); //
//        attributes.put(CHARA_GAP_NUM, "Number"); //
//
//        attributes.put(SERVICE_BATTERY, "Battery Service");
//
//        attributes.put(SERVICE_RADIO, "Radio Interface"); // a
//        attributes.put(CHARA_RADIO_CUSTOM_NAME, "Custom Name");
        attributes.put(GATT_UUID, "Data")

        //        attributes.put(CHARA_RADIO_RESPONSE_COUNT, "Response Count");
//        attributes.put(CHARA_RADIO_TIMER_TICK, "Timer Tick");
//        attributes.put(CHARA_RADIO_VERSION, "Version"); // firmwareVersion
//        attributes.put(CHARA_RADIO_LED_MODE, "Led Mode");
        attributesRileyLinkSpecific = HashMap<String?, String?>()

        attributesRileyLinkSpecific.put(SERVICE_UUID, "Radio Interface") // a
        //        attributesRileyLinkSpecific.put(CHARA_RADIO_CUSTOM_NAME, "Custom Name");
        attributesRileyLinkSpecific.put(GATT_UUID, "Data")
        //        attributesRileyLinkSpecific.put(CHARA_RADIO_RESPONSE_COUNT, "Response Count");
//        attributesRileyLinkSpecific.put(CHARA_RADIO_TIMER_TICK, "Timer Tick");
//        attributesRileyLinkSpecific.put(CHARA_RADIO_VERSION, "Version"); // firmwareVersion
//        attributesRileyLinkSpecific.put(CHARA_RADIO_LED_MODE, "Led Mode");
    }


    fun lookup(uuid: UUID): String? {
        return lookup(uuid.toString())
    }


    @JvmOverloads
    fun lookup(uuid: String?, defaultName: String? = uuid): String? {
        val name = attributes.get(uuid)
        return if (name == null) defaultName else name
    }


    // we check for specific UUID (Radio ones, because thoose seem to be unique
    fun isRileyLink(uuid: UUID): Boolean {
        return attributesRileyLinkSpecific.containsKey(uuid.toString())
    }
}
