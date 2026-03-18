package app.aaps.pump.medtronic.comm.activities


import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.common.hw.medlink.activities.BaseCallback
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.medtronic.MedLinkMedtronicPumpPlugin
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.MedLinkBasalProfileParser

import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Created by Dirceu on 22/12/20.
 */
class BasalCallback(private val aapsLogger: AAPSLogger, val medLinkMedtronicPumpPlugin: MedLinkMedtronicPumpPlugin) : BaseCallback<BasalProfile, Supplier<Stream<String>>>() {

    private val medLinkBasalProfileParser: MedLinkBasalProfileParser =
        MedLinkBasalProfileParser(
            aapsLogger
        )
    private var errorMessage: MutableList<MedLinkStandardReturn.ParsingError> = mutableListOf()
    override fun apply(resp: Supplier<Stream<String>>): MedLinkStandardReturn<BasalProfile> {
        errorMessage = ArrayList()
        aapsLogger.info(LTag.PUMP, "apply command")
        aapsLogger.info(LTag.PUMP, resp.get().collect(Collectors.joining()))
        val profile = medLinkBasalProfileParser.parseProfile(resp)

        //TODO check this;
        return if (profile != null) {
            val result = medLinkMedtronicPumpPlugin.comparePumpBasalProfile(profile)
            if (!result.success) {
                (errorMessage as ArrayList<MedLinkStandardReturn.ParsingError>).add(
                    MedLinkStandardReturn.ParsingError.BasalComparisonError)
                aapsLogger.warn(LTag.PUMPCOMM, "Error reading profile in max retries.")
                aapsLogger.warn(LTag.PUMPCOMM, "" + profile)
            }
            val errors = checkProfile(profile)
            medLinkMedtronicPumpPlugin.sendPumpUpdateEvent()
            profile.generateRawDataFromEntries()
            medLinkMedtronicPumpPlugin.medLinkPumpStatus.basalsByHour = profile.getProfilesByHour(medLinkMedtronicPumpPlugin.medLinkPumpStatus.pumpType)
            medLinkMedtronicPumpPlugin.basalProfile = profile
            MedLinkStandardReturn<BasalProfile>(resp, profile, errorMessage)
        } else {
            MedLinkStandardReturn(
                resp, BasalProfile(
                    aapsLogger
                ), mutableListOf(MedLinkStandardReturn.ParsingError.BasalParsingError)
            )
        }
    }

    private fun checkProfile(profile: BasalProfile): String {
        return ""
    }

    fun getErrorMessage(): List<MedLinkStandardReturn.ParsingError?>? {
        return errorMessage
    }

}