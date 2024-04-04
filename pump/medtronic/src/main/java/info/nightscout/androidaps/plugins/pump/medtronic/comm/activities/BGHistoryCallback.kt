package info.nightscout.androidaps.plugins.pump.medtronic.comm.activities

import android.os.Build
import androidx.annotation.RequiresApi
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.MedLinkConst
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities.BaseCallback
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities.MedLinkStandardReturn
import info.nightscout.androidaps.plugins.pump.medtronic.MedLinkMedtronicPumpPlugin
import info.nightscout.androidaps.plugins.pump.medtronic.data.MedLinkPumpStatusImpl
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.BgSync

import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
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

    var history: BgSync.BgHistory? = null

    private inner class InvalidBGHistoryException constructor(message: String?) : RuntimeException(message)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun apply(ans: Supplier<Stream<String>>): MedLinkStandardReturn<BgSync.BgHistory> {
        val state = parseAnswer(ans)
        aapsLogger.info(LTag.PUMPBTCOMM,"applying history")
        if (handleBG && !isCalibration) {
            medLinkPumpPlugin.handleNewSensorData(state.first)
        }
        this.history = state.first
        if (isCalibration && state.second) {
            val list = ans.get().toList()
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
                    val bg = java.lang.Double.valueOf(Objects.requireNonNull(bgMatcher.group(0)))
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
                            if (bgDate.toInstant().isAfter(Date().toInstant().minus(Duration.ofDays(3))) && bgDate.toInstant().isBefore(Date().toInstant().plus(Duration.ofMinutes(5)))) {
                                bgValues.add(
                                    BgSync.BgHistory.BgValue(
                                        bgDate.time, 0.0, bg,
                                        0.0,
                                        BgSync.BgArrow.NONE, BgSync.SourceSensor.MM_ENLITE, null, null,
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
            // val sorted = bgs.sorted { b: BgSync.BgHistory, a: BgSync.BgHistory -> (b.timestamp - a.timestamp).toInt() }
            // val history = BgSync.BgHistoryAccumulator()
            // sorted.forEachOrdered { f: BgSync.BgHistory ->
            //     if (history.last != null) {
            //         f.lastBG = history.last!!.currentBG
            //         f.lastBGDate = history.last!!.lastBGDate
            //     }
            //     history.addBG(f)
            // }
            // val result = Supplier {
            //     history.acc.stream().map { f: BgSync.BgHistory ->
            //         EnliteInMemoryGlucoseValue(
            //             f.currentBGDate.time, f.currentBG, false,
            //             f.lastBGDate.time, f.lastBG
            //         )
            //     }
            // }

            if (bgValues.isNotEmpty() && !isCalibration) {
                medLinkPumpPlugin.pumpStatusData.lastReadingStatus = BGReadingStatus.SUCCESS
            }
            Pair(BgSync.BgHistory(bgValues, calibrations), calibration)
        } catch (e: InvalidBGHistoryException) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Invalid bg history reading")
            Pair(BgSync.BgHistory(emptyList<BgSync.BgHistory.BgValue>(), emptyList<BgSync.BgHistory.Calibration>()), false)
        }
    }

}