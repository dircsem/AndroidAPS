package app.aaps.pump.medtronic.data.dto

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.util.function.BiConsumer
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * Created by Dirceu on 12/12/20.
 * used by medlink
 */
class MedLinkBasalProfileParser(private val aapsLogger: AAPSLogger) {
    var activeBasalName: String? = null
        private set

    fun parseProfile(profileText: Supplier<Stream<String>>): BasalProfile {
        val basalName = Pattern.compile("\\s\\w*\\s")
        val timePattern = Pattern.compile("\\d{2}:\\d{2}")

        val ratePattern = Pattern.compile("\\w*rate[\\s,\\d]\\d:", Pattern.CASE_INSENSITIVE)
        val baseProfile = BasalProfile(aapsLogger)
        return profileText.get()!!.collect(
            { baseProfile },
            BiConsumer { profile: BasalProfile, currentLine: String? ->
                if (currentLine!!.matches("basal\\s\\w*\\sprofile:\\r".toRegex())) {
                    val basalIndex = currentLine.indexOf("basal")
                    val basalNameMatcher = basalName.matcher(currentLine)
                    if (basalNameMatcher.find(basalIndex)) {
                        activeBasalName = basalNameMatcher.group()
                    }
                }
                val rateMatcher = ratePattern.matcher(currentLine)
                if (rateMatcher.find()) {
                    aapsLogger.info(LTag.PUMPBTCOMM, currentLine)
                    val rateIndex = currentLine.indexOf("rate")
                    val basalAmount =
                        currentLine.substring(rateIndex + 8, rateIndex + 13).toDouble()
                    val startFromIndex = currentLine.indexOf("start from")
                    val basalTimeMatcher =
                        timePattern.matcher(currentLine.substring(startFromIndex))
                    if (basalTimeMatcher.find()) {
                        val basalTimestamp: Array<String?> =
                            basalTimeMatcher.group().split(":".toRegex())
                                .dropLastWhile { it.isEmpty() }.toTypedArray()
                        baseProfile.addEntry(
                            MedLinkBasalProfileEntry(
                                basalAmount,
                                basalTimestamp[0]!!.toInt(),
                                basalTimestamp[1]!!.toInt()
                            )
                        )
                        profile.addEntry(
                            MedLinkBasalProfileEntry(
                                basalAmount,
                                basalTimestamp[0]!!.toInt(),
                                basalTimestamp[1]!!.toInt()
                            )
                        )
                    } else {
                        aapsLogger.error("Error parsing basal profile line $currentLine")
                    }
                }
            },
            { a: BasalProfile, b: BasalProfile -> BasalProfile(aapsLogger) })
    }
}
