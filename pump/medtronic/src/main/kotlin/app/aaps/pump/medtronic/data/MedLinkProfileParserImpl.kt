package app.aaps.pump.medtronic.data

import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.MedLinkProfileParser
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.extensions.pureProfileFromJson
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.data.dto.BasalProfileEntry
import info.nightscout.interfaces.db.WizardSettings
import org.json.JSONException
import org.json.JSONObject
import java.lang.Double
import java.util.*
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Stream
import javax.inject.Inject

/**
 * Created by Dirceu on 01/02/21.
 *  used by medlink
 */

class MedLinkProfileParserImpl<T> @Inject constructor(
    val activePlugin: ActivePlugin, private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil
): MedLinkProfileParser<T, BasalProfile> {

    private val hourPattern = Pattern.compile("\\d{2}:\\d{2}")
    private val carbRatioPattern = Pattern.compile(":\\s*(\\d+(?:\\.\\d+)?)\\s*gr/u")

    /** B6: "rate 1:  39mg/dl from 00:00", "rate 1: 100mg/dl ...", "rate 1: 2.2mmol/l ..." (spaces and decimals vary). */
    private val isfPattern = Pattern.compile(":\\s*(\\d+(?:\\.\\d+)?)\\s*(mg/dl|mmol/l)")

    /** B6: "rate 1:100-100 from 00:00", "rate 3: 80- 90 ...", "rate 1: 4.4-7.8 ..."; also the non-breaking hyphen (U+2011). */
    private val targetPattern = Pattern.compile(":\\s*(\\d+(?:\\.\\d+)?)\\s*[-\u2011]\\s*(\\d+(?:\\.\\d+)?)")
    private var units: String? = null

    /** B6: the profile as parsed from the last answer (keeps half hour times, which an AAPS profile cannot hold). */
    @Volatile var lastProfileJson: JSONObject? = null
        private set
    @Throws(JSONException::class) override fun parseProfile(answer: Supplier<Stream<String>>, basalProfile: BasalProfile?): ProfileSealed? {
        val profileData = answer.get().map { obj: String -> obj.lowercase(Locale.getDefault()) }.iterator()
        while (profileData.hasNext() && !profileData.next().contains("bolus wizard settings")) {
        }
        var buffer = StringBuffer()
        buffer.append("{")
        buffer = addData(buffer, "dia", true)
        buffer = addData(buffer, DEFAULT_DIA, false)
        parseBolusWizardSettings(profileData)
        buffer = parseCarbRatio(profileData, buffer)
        buffer = parseInsulinSensitivity(profileData, buffer)
        buffer.append(",")
        addData(buffer, "timezone", true)
        addData(buffer, TimeZone.getDefault().id, false)
        buffer = parseBGTargets(profileData, buffer)
        buffer.append(",")
        addData(buffer, "units", true)
        addData(buffer, units ?: GlucoseUnit.MGDL.asText, false)
        if (basalProfile != null) {
            buffer = parseBasal(basalProfile!!.getEntries(), buffer)
        }
        buffer.append("}")
        val json = JSONObject(buffer.toString())
        aapsLogger.info(LTag.PUMPBTCOMM, json.toString())
        //        Bolus wizard settings:
//23:37:48.398 Max. bolus: 15.0u
//23:37:48.398 Easy bolus step: 0.1u
//23:37:48.399 Carb ratios:
//23:37:48.623 Rate 1: 12gr/u from 00:00
//23:37:48.657 Rate 2: 09gr/u from 16:00
//23:37:48.694 Rate 3: 10gr/u from 18:00
//23:37:48.696 Insulin sensitivities:
//23:37:48.847 Rate 1:  40mg/dl from 00:00
//23:37:48.995 Rate 2:  30mg/dl from 09:00
//23:37:48.995 Rate 3:  40mg/dl from 18:00
//23:37:48.996 BG targets:
//23:37:49.189 Rate 1: 70‑140 from 00:00
//23:37:49.257 Rate 2: 70‑120 from 06:00
//23:37:49.258 Rate 3: 70‑140 from 10:00
//23:37:49.258 Rate 4: 70‑120 from 15:00
//23:37:49.294 Rate 5: 70‑140 from 21:00
//23:37:49.295 Ready
        lastProfileJson = json
        // GlucoseUnit.fromText knows only "mg/dl" and "mmol" (anything else would be read as mg/dl)
        val pure = pureProfileFromJson(json, dateUtil, units ?: GlucoseUnit.MGDL.asText)
        // B6: never silent: AAPS profiles take whole hours only, a pump time like 12:30 makes the whole profile invalid
        if (pure == null) aapsLogger.warn(LTag.PUMPBTCOMM, "pump profile is not a valid AAPS profile (times not on the hour?): only compared, AAPS cannot take it")
        return pure?.let {
            ProfileSealed.Pure(
                it,activePlugin)
        }
    }

    private fun addData(buffer: StringBuffer, dia: Any?, isParameter: Boolean): StringBuffer {
        val result = buffer.append("\"").append(dia).append("\"")
        if (isParameter) {
            result.append(":")
        }
        return result
    }

    private fun parseBGTargets(profileData: Iterator<String>, buffer: StringBuffer): StringBuffer {
        var result = buffer
        if (profileData.hasNext()) {
            val targetHigh = StringBuffer()
            addData(targetHigh, "target_high", true)
            targetHigh.append("[")
            val targetLow = StringBuffer()
            addData(targetLow, "target_low", true)
            targetLow.append("[")
            var ratioText = profileData.next()
            var toAppend = ""
            while (!ratioText.contains("ready")) {
                aapsLogger.info(LTag.PUMPBTCOMM, ratioText)
                val values = targetPattern.matcher(ratioText)
                val matcher = hourPattern.matcher(ratioText)
                if (values.find() && matcher.find()) {
                    val hour = matcher.group(0)
                    targetHigh.append(toAppend)
                    targetLow.append(toAppend)
                    addTimeValue(targetHigh, hour, values.group(2).toDouble())
                    addTimeValue(targetLow, hour, values.group(1).toDouble())
                    toAppend = ","
                } else if (ratioText.contains("rate")) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "bg target not read, skipped: $ratioText")
                }
                ratioText = profileData.next()
            }
            targetHigh.append("]")
            targetLow.append("]")
            result = buffer.append(",").append(targetHigh).append(",").append(targetLow)
        }
        return result
    }

    private fun parseInsulinSensitivity(profileData: Iterator<String>, buffer: StringBuffer): StringBuffer {
        if (profileData.hasNext()) {
            var ratioText = profileData.next()
            buffer.append(",")
            addData(buffer, "sens", true)
            buffer.append("[")
            var toAppend = ""
            while (!ratioText.contains("bg targets")) {
                // B6: a pattern, not a fixed slice: "rate 1: 39mg/dl" (one space) made Integer.valueOf(" 39m") throw and
                // the whole profile read was lost; mmol/l values have decimals
                val values = isfPattern.matcher(ratioText)
                val matcher = hourPattern.matcher(ratioText)
                if (values.find() && matcher.find()) {
                    units = if (values.group(2).startsWith("mmol")) GlucoseUnit.MMOL.asText else GlucoseUnit.MGDL.asText
                    buffer.append(toAppend)
                    addTimeValue(buffer, matcher.group(0), values.group(1).toDouble())
                    toAppend = ","
                } else if (ratioText.contains("rate")) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "insulin sensitivity not read, skipped: $ratioText")
                }
                ratioText = profileData.next()
            }
            buffer.append("]")
        }
        return buffer
    }

    private fun parseBasal(basalProfile: List<BasalProfileEntry>, buffer: StringBuffer): StringBuffer {
        var buffer = buffer
        buffer.append(",")
        buffer = addData(buffer, "basal", true)
        var toAppend = "["
        // the basal entries can come twice for the same time: keep one per time (same value)
        for (basalEntry in basalProfile.distinctBy { it.startTime?.toString("HH:mm") }) {
            buffer.append(toAppend)
            addTimeValue(buffer, basalEntry.startTime!!.toString("HH:mm"),
                         basalEntry.rate)
            toAppend = ","
        }
        // no entry: still a valid (empty) JSON array
        if (toAppend == "[") buffer.append("[")
        buffer.append("]")
        return buffer
    }

    private fun parseCarbRatio(profileText: Iterator<String>, buffer: StringBuffer): StringBuffer {
        var buffer = buffer
        if (profileText.hasNext()) {
            buffer.append(",")
            buffer = addData(buffer, "carbratio", true)
            val carbRatio = profileText.next()
            if (carbRatio.contains("carb ratios")) {
                var ratioText = profileText.next()
                var toAppend = "["
                //                buffer.append(toAppend);
                while (!ratioText.contains("insulin sensitivities")) {
                    // "rate 1: 12gr/u from 00:00" or with decimals "rate 1: 12.5gr/u from 00:00".
                    // Exchanges ("u/ex") are not known well enough to convert: skipped, so no wrong ratio is used.
                    val carbMatcher = carbRatioPattern.matcher(ratioText)
                    if (carbMatcher.find()) {
                        if (carbRatio(carbMatcher.group(1), buffer, ratioText, toAppend)) toAppend = ","
                    } else if (ratioText.contains("rate")) {
                        aapsLogger.warn(LTag.PUMPBTCOMM, "carb ratio not in gr/u, skipped: $ratioText")
                    }
                    ratioText = getNextValidLine(profileText)
                }
                // no ratio added: still a valid (empty) JSON array; the profile is then not valid and not offered
                if (toAppend == "[") buffer.append("[")
                buffer.append("]")
            } else {
                aapsLogger.info(LTag.PUMPBTCOMM, "profile doesn't have carb ratios")
            }
        }
        return buffer
    }

    /** Adds one carb ratio (g/U, decimals kept). @return true when it was added */
    private fun carbRatio(carbs: String, buffer: StringBuffer, ratioText: String, toAppend: String): Boolean {
        aapsLogger.info(LTag.PUMPBTCOMM, "$ratioText $carbs")
        val ratio = carbs.toDouble()
        val matcher = hourPattern.matcher(ratioText)
        if (!matcher.find()) {
            aapsLogger.info(LTag.PUMPBTCOMM, "carbratio matcher doesn't found hour")
            return false
        }
        buffer.append(toAppend)
        addTimeValue(buffer, matcher.group(0), ratio)
        return true
    }

    private fun getNextValidLine(profileText: Iterator<String>): String {
        val ratioText = profileText.next()
        return if (ratioText.contains("error command")) {
            getNextValidLine(profileText)
        } else {
            ratioText
        }
    }

    private fun addTimeValue(buffer: StringBuffer, hour: String, ratio: Any) {
        buffer.append("{")
        addData(buffer, "time", true)
        addData(buffer, hour, false)
        buffer.append(",")
        addData(buffer, "value", true)
        buffer.append(ratio)
        buffer.append("}")
    }

    private fun parseBolusWizardSettings(profileText: Iterator<String>) {
        if (profileText.hasNext()) {
            val result = WizardSettings()
            val maxBolusText = profileText.next()
            val pattern = Pattern.compile("\\d+\\.\\d")
            if (maxBolusText.contains("max. bolus")) {
                val matcher = pattern.matcher(maxBolusText)
                if (matcher.find()) {
                    result.maxBolus = matcher.group(0).toDouble()
                }
            }
            val easyBolusStepText = profileText.next()
            if (easyBolusStepText.contains("easy bolus")) {
                val matcher = pattern.matcher(easyBolusStepText)
                if (matcher.find()) {
                    result.easyBolusStep = matcher.group(0).toDouble()
                }
            }
            if (result.maxBolus === 0.0 && result.easyBolusStep === 0.0) {
                aapsLogger.info(LTag.PUMPBTCOMM, "Failed to parse max and bolus step " +
                    maxBolusText + " " + easyBolusStepText)
            }
        }
    }
}

/** MedLink: DIA written into the profile read from the Veo (Constants.defaultDIA was removed in AAPS 4.0). */
private const val DEFAULT_DIA = 5.0
