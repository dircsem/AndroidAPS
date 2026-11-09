package app.aaps.pump.common.hw.medlink.bolus

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * What the MedLink answers to the BOLUS command itself, after "bolus verification":
 *  - delivering: `... pump is bolusing state / last bolus: 0.4u / ready` (no time while it delivers);
 *  - not delivered: `... pump normal state / last bolus: 2.0u 09-11-26 13:22 / ready` (the previous bolus);
 *  - delivered already (small bolus): `... pump normal state / last bolus: 0.1u <time of our bolus> / ready`.
 * Read on the joined answer (BLE chunks of 20 bytes split the lines).
 */
data class BolusCommandAnswer(
    val lastBolusAmount: Double,
    /** Pump time of the last bolus (year normalized), null when the answer has no time. */
    val lastBolusTime: Long?,
    val bolusing: Boolean,
    val raw: String
) {

    companion object {

        private val lastBolus: Pattern =
            Pattern.compile("last bolus:\\s*(\\d{1,2}\\.\\d{1,3})u(?:\\s*(\\d{2}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}))?", Pattern.CASE_INSENSITIVE)

        /** @return null when the answer has no "last bolus" (connection lost, error...). */
        fun parse(text: String, readAt: Long): BolusCommandAnswer? {
            val m = lastBolus.matcher(text)
            if (!m.find()) return null
            val time = m.group(2)?.let {
                SimpleDateFormat("dd-MM-yy HH:mm", Locale.US).apply { isLenient = false }.parse(it.replace(Regex("\\s+"), " "), ParsePosition(0))?.time
            }?.let { PumpClock.normalizeYear(it, readAt) }
            return BolusCommandAnswer(
                lastBolusAmount = m.group(1)!!.toDouble(),
                lastBolusTime = time,
                bolusing = PumpStateAnswer.isBolusing(text.lowercase(Locale.US)),
                raw = text
            )
        }
    }
}
