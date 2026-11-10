package app.aaps.pump.medtronic.data

/**
 * 4y: a suspension (temp basal below 100%) is not sent when the MedLink or the phone battery is at or below the
 * configured minimum: if one of them dies while the pump is suspended, nobody starts it again. The pump battery
 * does not count here.
 */
object MedLinkSuspendBatteryRule {

    enum class Device { PHONE, MEDLINK }

    data class Low(val device: Device, val level: Int)

    /**
     * @param phoneLevel phone battery % (null = unknown)
     * @param medLinkLevel MedLink battery % (null = not read yet)
     * @return the low battery, or null when the suspension may be sent
     */
    fun low(phoneLevel: Int?, medLinkLevel: Int?, minimum: Int): Low? = when {
        medLinkLevel != null && medLinkLevel <= minimum -> Low(Device.MEDLINK, medLinkLevel)
        phoneLevel != null && phoneLevel <= minimum     -> Low(Device.PHONE, phoneLevel)
        else                                            -> null
    }
}
