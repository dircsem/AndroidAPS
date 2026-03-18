package app.aaps.pump.medtronic.comm.activities

import android.os.Build
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.source.BgSync

import app.aaps.pump.common.hw.medlink.MedLinkConst
import app.aaps.pump.common.hw.medlink.activities.BaseCallback
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.medtronic.MedLinkMedtronicPumpPlugin
import java.lang.Double
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * Created by Dirceu on 24/01/21.
 */
class BGHistoryCallback(
    private val medLinkPumpPlugin: MedLinkMedtronicPumpPlugin,
    private val aapsLogger: AAPSLogger,
    private val handleBG: Boolean,
    private val isCalibration: Boolean
) : BaseCallback<BgSync.BgHistory, Supplier<Stream<String>>>() {

    lateinit var history: BgSync.BgHistory

    private inner class InvalidBGHistoryException constructor(message: String?) : RuntimeException(message)

    override fun apply(ans: Supplier<Stream<String>>): MedLinkStandardReturn<BgSync.BgHistory> {
        val state = parseAnswer(ans)
        aapsLogger.info(LTag.PUMPBTCOMM,"applying history")
        if (handleBG && !isCalibration) {
            medLinkPumpPlugin.handleNewSensorData(state.first)
        }
        this.history = state.first
        if (isCalibration && state.second) {

            val list = mutableListOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                list.addAll(ans.get().toList())
            } else {
                ans.get().forEach {  list.add(it) }
            }
            list.add(MedLinkConst.FREQUENCY_CALIBRATION_SUCCESS)
            return MedLinkStandardReturn({ list.stream() }, state.first)
        }
        return MedLinkStandardReturn(ans, state.first)
    }

    private fun parseAnswer(ans: Supplier<Stream<String>>): Pair<BgSync.BgHistory, Boolean> {
        val answers = ans.get()
        var calibration = true
        return try {
            val calibrations: MutableList<BgSync.BgHistory.Calibration> = mutableListOf()
            val bgValues: MutableList<BgSync.BgHistory.BgValue> = mutableListOf()

            answers.forEach { f: String ->
                val bgLinePattern = Pattern.compile("[bg|cl]:\\s?\\d{2,3}\\s+\\d{1,2}:\\d{2}\\s+\\d{2}-\\d{2}-\\d{4}")
                val matcher = bgLinePattern.matcher(f)
                //BG: 68 15:35 00‑00‑2000

                if ((f.length == 25 || f.length == 26) && matcher.find()) {
                    val data = matcher.group(0)

//                Double bg = Double.valueOf(data.substring(3, 6).trim());
                    val bgPat = Pattern.compile("\\d{2,3}")
                    assert(data != null)
                    val bgMatcher = bgPat.matcher(data)
                    bgMatcher.find()
                    val bg = Double.valueOf(Objects.requireNonNull(bgMatcher.group(0)))
                    val datePattern = "HH:mm dd-MM-yyyy"
                    val dtPattern = Pattern.compile("\\d{1,2}:\\d{2}\\s+\\d{2}-\\d{2}-\\d{4}")
                    val dtMatcher = dtPattern.matcher(data)
                    dtMatcher.find()
                    val formatter = SimpleDateFormat(datePattern, Locale.getDefault())
                    var bgDate: Date? = null

                    try {
                        bgDate = formatter.parse(Objects.requireNonNull(dtMatcher.group(0)))
                        val firstDate = Date()
                        firstDate.time = 0L
                        assert(bgDate != null)
                        if (bgDate.time > System.currentTimeMillis()) {
                            throw InvalidBGHistoryException("TimeInFuture")
                        }
                        //                    aapsLogger.info(LTag.PUMPBTCOMM, f);
                        if (f.trim { it <= ' ' }.startsWith("cl:")) {
                            calibrations.add(
                                BgSync.BgHistory.Calibration(
                                    bgDate.time, bg,
                                    medLinkPumpPlugin.glucoseUnit()
                                )
                            )
                        } else {
                            if (bgDate.toInstant().isAfter(Date().toInstant().minus(Duration.ofDays(3))) && bgDate.toInstant().isBefore(
                                    Date().toInstant().plus(Duration.ofMinutes(5)))) {
                                bgValues.add(
                                    BgSync.BgHistory.BgValue(
                                        bgDate.time,
                                        0.0,
                                        bg,
                                        0.0,
                                        BgSync.BgArrow.NONE,
                                        BgSync.SourceSensor.MM_ENLITE,
                                        null,
                                        null,
                                        null
                                    )
                                )
                            }
                        }
                    } catch (e: ParseException) {
                        e.printStackTrace()

                    }

                } else if ((f.contains("bg:") || f.contains("cl:")) && (f.length < 25 || f.length > 26)) {
                    calibration = false
                }
            }
            bgValues.sortBy { it.timestamp }
            // val sorted = bgs.sorted { b: BGHistory, a: BGHistory -> (b.timestamp - a.timestamp).toInt() }
            // val history = BGHistoryAccumulator()
            // sorted.forEachOrdered { f: BGHistory ->
            //     if (history.last != null) {
            //         f.lastBG = history.last!!.currentBG
            //         f.lastBGDate = history.last!!.lastBGDate
            //     }
            //     history.addBG(f)
            // }
            // val result = Supplier {
            //     history.acc.stream().map { f: BGHistory ->
            //         EnliteInMemoryGlucoseValue(
            //             f.currentBGDate.time, f.currentBG, false,
            //             f.lastBGDate.time, f.lastBG
            //         )
            //     }
            // }

            if (bgValues.isNotEmpty() && !isCalibration) {
                medLinkPumpPlugin.medLinkPumpStatus.lastReadingStatus = BGReadingStatus.SUCCESS
            }
            Pair(BgSync.BgHistory(bgValues, calibrations), calibration)
        } catch (e: InvalidBGHistoryException) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Invalid bg history reading")
            Pair(BgSync.BgHistory(emptyList<BgSync.BgHistory.BgValue>(), emptyList<BgSync.BgHistory.Calibration>()), false)
        }
    }

}