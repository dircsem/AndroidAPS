package app.aaps.pump.medtronic.driver

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.events.EventRileyLinkDeviceStatusChange
import app.aaps.pump.common.hw.medlink.MedLinkUtil
import app.aaps.pump.common.hw.medlink.data.MLHistoryItem
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.medtronic.data.MedLinkPumpStatusImpl
import app.aaps.pump.medtronic.defs.BasalProfileStatus
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import app.aaps.pump.medtronic.keys.MedtronicLongNonKey
import app.aaps.pump.medtronic.keys.MedtronicStringPreferenceKey
import java.util.Calendar
import java.util.GregorianCalendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by dirceu on 25/09/20.
 * copied from [MedtronicPumpStatus]
 */
@Singleton
class MedLinkMedtronicPumpStatus @Inject constructor(
    preferences: Preferences,
    private val resourceHelper: ResourceHelper,
    private val rxBus: RxBus,
    private val medLinkUtil: MedLinkUtil
) : MedLinkPumpStatusImpl(PumpType.MEDLINK_MEDTRONIC_523_723_REVEL) {
    //    override var errorDescription: String? = null
    var serialNumber: String = preferences.get(MedtronicStringPreferenceKey.Serial)
    var pumpFrequency: String = preferences.get(MedtronicStringPreferenceKey.PumpFrequency)
    var maxBolus: Double? = null
    var maxBasal: Double? = null


    // statuses
    override var pumpDeviceState: PumpDeviceState = PumpDeviceState.NeverContacted
        set(pumpDeviceState) {
            field = pumpDeviceState

            medLinkUtil.getRileyLinkHistory()
                .add(MLHistoryItem(pumpDeviceState, RileyLinkTargetDevice.MedtronicPump))

            rxBus.send(EventRileyLinkDeviceStatusChange(pumpDeviceState))
        }
    var medtronicDeviceType: MedLinkMedtronicDeviceType? = null


    // fixme


    var medtronicPumpMap: MutableMap<String, PumpType>? = null
        private set
    var medtronicDeviceTypeMap: MutableMap<String?, MedLinkMedtronicDeviceType?>? = null
        private set
    var basalProfileStatus: BasalProfileStatus = BasalProfileStatus.NotInitialized
    var batteryType: BatteryType = BatteryType.None


    init {
        this.activeProfileName = "STD"
        this.reservoirRemainingUnits = 75.0
        this.reservoirRemainingUnits = 300.0
        this.batteryRemaining = 75

        if (this.medtronicPumpMap == null) createMedtronicPumpMap()

        if (this.medtronicDeviceTypeMap == null) createMedtronicDeviceTypeMap()

        lastConnection = preferences.get(MedtronicLongNonKey.LastGoodPumpCommunicationTime)

        this.lastDataTime = this.lastConnection
    }


    private fun createMedtronicDeviceTypeMap() {
        medtronicDeviceTypeMap = HashMap<String?, MedLinkMedtronicDeviceType?>()
        medtronicDeviceTypeMap!!.put("512", MedLinkMedtronicDeviceType.MedLinkMedtronic_512)
        medtronicDeviceTypeMap!!.put("712", MedLinkMedtronicDeviceType.MedLinkMedtronic_712)
        medtronicDeviceTypeMap!!.put("515", MedLinkMedtronicDeviceType.MedLinkMedtronic_515)
        medtronicDeviceTypeMap!!.put("715", MedLinkMedtronicDeviceType.MedLinkMedtronic_715)

        medtronicDeviceTypeMap!!.put("522", MedLinkMedtronicDeviceType.MedLinkMedtronic_522)
        medtronicDeviceTypeMap!!.put("722", MedLinkMedtronicDeviceType.MedLinkMedtronic_722)
        medtronicDeviceTypeMap!!.put("523", MedLinkMedtronicDeviceType.MedLinkMedtronic_523_Revel)
        medtronicDeviceTypeMap!!.put("723", MedLinkMedtronicDeviceType.MedLinkMedtronic_723_Revel)
        medtronicDeviceTypeMap!!.put("554", MedLinkMedtronicDeviceType.MedLinkMedtronic_554_Veo)
        medtronicDeviceTypeMap!!.put("754", MedLinkMedtronicDeviceType.MedLinkMedtronic_754_Veo)
    }


    private fun createMedtronicPumpMap() {
        medtronicPumpMap = HashMap<String, PumpType>()
        medtronicPumpMap!!.put("523", PumpType.MEDLINK_MEDTRONIC_523_723_REVEL)
        medtronicPumpMap!!.put("723", PumpType.MEDLINK_MEDTRONIC_523_723_REVEL)
        medtronicPumpMap!!.put("554", PumpType.MEDLINK_MEDTRONIC_554_754_VEO)
        medtronicPumpMap!!.put("754", PumpType.MEDLINK_MEDTRONIC_554_754_VEO)
    }

    val basalProfileForHour: Double
        get() = if (basalsByHour != null) {
            val c = GregorianCalendar()
            val hour = c.get(Calendar.HOUR_OF_DAY)
            basalsByHour!![hour]

        } else {
            currentBasal

        }

//        return 0;


    // Battery type
    private var mapByDescription: MutableMap<String?, BatteryType?>? = null


    fun getBatteryTypeByDescription(batteryTypeStr: String?): BatteryType {
        if (mapByDescription == null) {
            mapByDescription = HashMap<String?, BatteryType?>()
            for (value in BatteryType.values()) {
                mapByDescription!!.put(resourceHelper.gs(value.friendlyName), value)
            }
        }
        if (mapByDescription!!.containsKey(batteryTypeStr)) {
            return mapByDescription!!.get(batteryTypeStr) ?: BatteryType.None
        }
        return BatteryType.None
    }

    override val errorInfo: String
        get() = (if (errorDescription == null) "-" else errorDescription)!!


}
