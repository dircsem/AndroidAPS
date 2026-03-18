package info.nightscout.androidaps.plugins.pump.medtronic.comm.activities

import android.content.Context
import app.aaps.core.interfaces.plugin.MedLinkProfileParser
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.ui.toast.ToastUtils.showToastInUiThread
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.medtronic.MedLinkMedtronicPumpPlugin
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.defs.BasalProfileStatus
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import app.aaps.pump.medtronic.driver.MedLinkMedtronicPumpStatus
import org.json.JSONException
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 01/02/21.
 */
class ProfileCallback(private val ctx: Context, private val medLinkMedtronicPumpPlugin: MedLinkMedtronicPumpPlugin,
                      private val parser: MedLinkProfileParser<MedLinkStandardReturn<MedLinkMedtronicDeviceType>, BasalProfile>
) :
    Function<Supplier<Stream<String>>, MedLinkStandardReturn<Profile?>> {

    override fun apply(answer: Supplier<Stream<String>>): MedLinkStandardReturn<Profile?> {
        val x: DataSyncSelector.PairProfileSwitch

        var result: MedLinkStandardReturn<Profile?>
        try {
            val profile = parser.parseProfile(answer, medLinkMedtronicPumpPlugin.basalProfile)
            result = MedLinkStandardReturn(answer, profile)
            (medLinkMedtronicPumpPlugin.medLinkPumpStatus as MedLinkMedtronicPumpStatus).basalProfileStatus = BasalProfileStatus.ProfileOK
            if (profile != null &&  !medLinkMedtronicPumpPlugin.isThisProfileSet(profile)) {
                showToastInUiThread(
                    ctx,
                    medLinkMedtronicPumpPlugin.rh.gs(app.aaps.pump.medtronic.R.string.medtronic_cmd_basal_profile_could_not_be_set)
                )
            }
             if (profile != null){
                 medLinkMedtronicPumpPlugin.initProfile(profile)
             }
        } catch (e: JSONException) {
            e.printStackTrace()
            result = MedLinkStandardReturn(answer, null, MedLinkStandardReturn.ParsingError.BasalParsingError)
        }
        medLinkMedtronicPumpPlugin.lastProfileRead = System.currentTimeMillis()
        //        errorMessage = new ArrayList<>();
//        aapsLogger.info(LTag.PUMP,"apply command");
//        aapsLogger.info(LTag.PUMP,resp.get().collect(Collectors.joining()));
//        BasalProfile profile = medLinkBasalProfileParser.parseProfile(resp);
        return result
    }
}