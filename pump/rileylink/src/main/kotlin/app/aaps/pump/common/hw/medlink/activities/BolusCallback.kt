package app.aaps.pump.common.hw.medlink.activities

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.common.hw.medlink.PumpResponses
import app.aaps.pump.common.hw.medlink.data.BolusAnswer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Created by Dirceu on 21/12/20.
 */
class BolusCallback(private val aapsLogger: AAPSLogger, private val bolusInfo: DetailedBolusInfo) :
    BaseCallback<BolusAnswer, Supplier<Stream<String>>>() {
    private val deliveredBolusPattern: Pattern = Pattern.compile(
        ":\\s+\\d{1,2}\\.\\du\\s+\\d{1,2}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{1,2}",
        Pattern.CASE_INSENSITIVE
    )
    private val deliveringBolusPattern: Pattern =
        Pattern.compile(":\\s+\\d{1,2}\\.\\du", Pattern.CASE_INSENSITIVE)

    override fun apply(answers: Supplier<Stream<String>>): MedLinkStandardReturn<BolusAnswer> {
        aapsLogger.info(LTag.PUMPBTCOMM, "BolusCallback")
        //TODO fix error response
        val pumpResponse = AtomicReference<BolusAnswer>()
        if (answers.get().anyMatch { f: String ->
                f.lowercase(Locale.getDefault()).contains("pump is bolusing state")
            } ||
            answers.get()
                .anyMatch { toBeMatched -> this.isBolusing(toBeMatched) }) {
            answers.get()
                .filter { f: String -> f.lowercase(Locale.getDefault()).contains("last bolus:") }
                .findFirst().map<AtomicReference<BolusAnswer>>(
                    Function { f: String ->
                        pumpResponse.set(deliveringBolus(f!!))
                        pumpResponse
                    })
        } else if (answers.get().anyMatch { f: String ->
                f.lowercase(Locale.getDefault()).contains("last bolus:")
            }) {
            answers.get()
                .filter { f: String? -> f!!.lowercase(Locale.getDefault()).contains("last bolus:") }
                .findFirst().map<AtomicReference<BolusAnswer>>(
                    Function { f: String ->
                        pumpResponse.set(deliveredBolus(f))
                        pumpResponse
                    })
        } else {
            pumpResponse.set(
                BolusAnswer(
                    PumpResponses.UnknownAnswer,
                    answers.get().collect(Collectors.joining()), bolusInfo
                )
            )
        }
        return createPumpResponse(answers, pumpResponse)
    }

    private fun isBolusing(toBeMatched: String): Boolean {
        return (!deliveredBolusPattern.matcher(toBeMatched)
            .find() && deliveringBolusPattern.matcher(toBeMatched).find()) || toBeMatched.lowercase(
            Locale.getDefault()
        ).contains("pump is bolusing state")
    }

    private fun createPumpResponse(
        answers: Supplier<Stream<String>>,
        pumpResponse: AtomicReference<BolusAnswer>
    ): MedLinkStandardReturn<BolusAnswer> {
        if (pumpResponse.get() != null) {
            return MedLinkStandardReturn(answers, pumpResponse.get())
        } else {
            return MedLinkStandardReturn(
                answers, BolusAnswer(
                    PumpResponses.UnknownAnswer, answers.get().collect(Collectors.joining()),
                    bolusInfo
                ), MedLinkStandardReturn.ParsingError.BolusParsingError
            )
        }
    }

    private fun deliveringBolus(input: String): BolusAnswer {
        aapsLogger.info(LTag.PUMPBTCOMM, "match bolus")
        val deliveredBolusPattern = Pattern.compile("\\d{1,2}\\.\\du", Pattern.CASE_INSENSITIVE)
        val matcher = deliveredBolusPattern.matcher(input)
        if (matcher.find()) {
            val units: String? = checkNotNull(matcher.group(0))
            val delivered = units!!.substring(0, units.length - 1).toDouble()
            //                12:09 01-06
            return BolusAnswer(PumpResponses.DeliveringBolus, delivered, input, bolusInfo!!.carbs)
        }
        return BolusAnswer(PumpResponses.UnknownAnswer, input, bolusInfo)
    }


    private fun deliveredBolus(input: String): BolusAnswer {
        aapsLogger.info(LTag.PUMPBTCOMM, "match bolus")
        //        Pattern deliveredBolusPattern = Pattern.compile(lastBolusPattern, Pattern.CASE_INSENSITIVE);
        val matcher = deliveredBolusPattern.matcher(input)
        if (matcher.find()) {
            val unitsPattern = Pattern.compile("\\d{1,2}\\.\\du")
            val unitsMatcher = unitsPattern.matcher(input)
            if (unitsMatcher.find()) {
                var units: String? = checkNotNull(unitsMatcher.group(0))
                val delivered =
                    units!!.substring(0, units.length - 1).trim { it <= ' ' }.toDouble()
                //                12:09 01-06
                val deliveredTimePattern = Pattern.compile(
                    "\\d{1,2}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{1,2}",
                    Pattern.CASE_INSENSITIVE
                )
                val deliveredTimeMatcher = deliveredTimePattern.matcher(input)
                if (deliveredTimeMatcher.find()) {
                    val deliveredTime: String? = checkNotNull(deliveredTimeMatcher.group(0))
                    //                    String[] dateTime = deliveredTime.split(" ");
//                    String[] time = dateTime[0].split(":");
//                    String[] date = dateTime[1].split("-");
                    val formatter = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm")
                    val bolusDeliveredAt = LocalDateTime.parse(deliveredTime, formatter).atZone(
                        ZoneId.systemDefault()
                    )
                    bolusInfo.timestamp = bolusDeliveredAt.toInstant().toEpochMilli()
                    if(System.currentTimeMillis() - bolusInfo.timestamp> 610000){
                        aapsLogger.info(LTag.PUMPBTCOMM, "bolus not delivered")
                    }
                    return BolusAnswer( PumpResponses.BolusDelivered, delivered, bolusInfo)
                } else if (unitsMatcher.find()) {
                    units = unitsMatcher.group(1)
                    checkNotNull(units)
                    if(bolusInfo.insulin != units.toDouble()){
                        aapsLogger.info(LTag.PUMPBTCOMM, "bolus not delivered")
                    }

                    val lastBolusAmount =
                        units.substring(0, units.length - 1).trim { it <= ' ' }.toDouble()
                    if (lastBolusAmount == bolusInfo.insulin) {
                        return BolusAnswer(PumpResponses.DeliveringBolus, input, bolusInfo)
                    }
                }
            }
        }
        return BolusAnswer(PumpResponses.UnknownAnswer, input, bolusInfo)
    } //    private int getYear(String[] date) {
    //        ZonedDateTime now = ZonedDateTime.now();
    //        if (now.getMonthValue() == 1 && Integer.parseInt(date[1]) == 12) {
    //            return now.getYear() - 1;
    //        } else {
    //            return now.getYear();
    //        }
    //    }
}
