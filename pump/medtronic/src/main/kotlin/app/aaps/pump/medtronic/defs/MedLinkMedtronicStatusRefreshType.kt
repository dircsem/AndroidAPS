package app.aaps.pump.medtronic.defs

import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import app.aaps.pump.medtronic.defs.MedLinkMedtronicCommandType.Companion.getSettings


/**
 * Created by dirceu
 *
 * used by medlink
 */
enum class MedLinkMedtronicStatusRefreshType(
    val refreshTime: Int,
    commandType: MedLinkMedtronicCommandType
) {
    PumpHistory(5, MedLinkMedtronicCommandType.PumpStatus),  //
    Configuration(0, MedLinkMedtronicCommandType.InvalidCommand),  //
    RemainingInsulin(-1, MedLinkMedtronicCommandType.GetRemainingInsulin),  //
    BatteryStatus(55, MedLinkMedtronicCommandType.GetBatteryStatus),  //
    PumpTime(60, MedLinkMedtronicCommandType.GetRealTimeClock); //

    private val commandType: MedLinkMedtronicCommandType?


    init {
        this.commandType = commandType
    }


    fun getCommandType(medtronicDeviceType: MedLinkMedtronicDeviceType?): MedLinkMedtronicCommandType? {
        if (this == MedLinkMedtronicStatusRefreshType.Configuration) {
            return getSettings(medtronicDeviceType)
        } else return commandType
    }
}
