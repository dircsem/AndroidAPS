package app.aaps.pump.medtronic.data

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.Profile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * MedLink: where the profile read from the pump (basal, carb ratios, ISF, BG targets) differs from the AAPS profile.
 * Used to ask the user to take over the pump profile (setting "Accept profile changes from the pump").
 *
 * Values are compared every 30 min (Medtronic schedules can start on the half hour). Slots in a row with the same
 * pair of values are one range.
 */
object MedLinkPumpProfileDiff {

    enum class Kind { BASAL, IC, ISF, TARGET_LOW, TARGET_HIGH }

    /** [fromSlot] inclusive, [toSlot] exclusive, in 30 min slots (48 = midnight). */
    data class Range(val kind: Kind, val fromSlot: Int, val toSlot: Int, val pump: Double, val aaps: Double)

    const val SLOTS = 48
    private const val SLOT_SECONDS = 30 * 60

    private val tolerance = mapOf(
        Kind.BASAL to 0.0005,
        Kind.IC to 0.05,
        Kind.ISF to 0.5,
        Kind.TARGET_LOW to 0.5,
        Kind.TARGET_HIGH to 0.5
    )

    /** The pump profile as values per slot, in [Kind] order. The same values give the same list (used to remember "ignore"). */
    fun values(profile: Profile, roundBasal: (Double) -> Double): Map<Kind, List<Double>> =
        Kind.entries.associateWith { kind -> (0 until SLOTS).map { valueAt(profile, kind, it * SLOT_SECONDS, roundBasal) } }

    /** Basal is rounded with [roundBasal] (pump basal step) on both sides; ISF and targets are in mg/dl. */
    fun differences(pump: Profile, aaps: Profile, roundBasal: (Double) -> Double): List<Range> =
        differences(values(pump, roundBasal), values(aaps, roundBasal))

    /** The same with values per slot ([values]), for example the pump values read straight from the answer. */
    fun differences(pumpValues: Map<Kind, List<Double>>, aapsValues: Map<Kind, List<Double>>): List<Range> =
        Kind.entries.flatMap { kind -> differences(kind, pumpValues.getValue(kind), aapsValues.getValue(kind)) }

    /**
     * B6: the pump values per slot straight from the profile JSON the MedLink parser built (`carbratio`, `sens`,
     * `target_low`, `target_high`, `basal`). Unlike an AAPS profile this keeps half hour times (Medtronic allows 12:30;
     * AAPS profiles only whole hours). ISF and targets in mg/dl, basal rounded with [roundBasal].
     * @return null when a list is missing or not readable
     */
    fun values(json: JSONObject, roundBasal: (Double) -> Double): Map<Kind, List<Double>>? = try {
        val toMgdl = if (json.optString("units") == GlucoseUnit.MMOL.asText) Constants.MMOLL_TO_MGDL else 1.0
        fun slots(key: String, factor: Double, round: (Double) -> Double = { it }): List<Double>? {
            val entries = timeValues(json.optJSONArray(key) ?: return null)
            if (entries.isEmpty()) return null
            return (0 until SLOTS).map { slot ->
                val seconds = slot * SLOT_SECONDS
                round((entries.lastOrNull { it.first <= seconds } ?: entries.first()).second * factor)
            }
        }
        val basal = slots("basal", 1.0, roundBasal)
        val ic = slots("carbratio", 1.0)
        val isf = slots("sens", toMgdl)
        val low = slots("target_low", toMgdl)
        val high = slots("target_high", toMgdl)
        if (basal == null || ic == null || isf == null || low == null || high == null) null
        else mapOf(Kind.BASAL to basal, Kind.IC to ic, Kind.ISF to isf, Kind.TARGET_LOW to low, Kind.TARGET_HIGH to high)
    } catch (e: JSONException) {
        null
    }

    /** B6: times of the profile JSON that are not on the hour ("12:30"), which an AAPS profile cannot hold. */
    fun offHourTimes(json: JSONObject): List<String> =
        listOf("basal", "carbratio", "sens", "target_low", "target_high")
            .flatMap { key -> json.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("time") } } ?: emptyList() }
            .filter { !it.endsWith(":00") }
            .distinct()
            .sorted()

    /** "HH:mm" entries as (seconds from midnight, value), in time order. */
    private fun timeValues(array: JSONArray): List<Pair<Int, Double>> =
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val (h, m) = entry.getString("time").split(":").map { it.trim().toInt() }
            (h * 3600 + m * 60) to entry.getDouble("value")
        }.sortedBy { it.first }

    fun differences(kind: Kind, pumpBySlot: List<Double>, aapsBySlot: List<Double>): List<Range> {
        val limit = tolerance.getValue(kind)
        val ranges = mutableListOf<Range>()
        for (slot in 0 until minOf(SLOTS, pumpBySlot.size, aapsBySlot.size)) {
            val pump = pumpBySlot[slot]
            val aaps = aapsBySlot[slot]
            if (abs(pump - aaps) <= limit) continue
            val last = ranges.lastOrNull()
            if (last != null && last.toSlot == slot && abs(last.pump - pump) <= limit && abs(last.aaps - aaps) <= limit)
                ranges[ranges.lastIndex] = last.copy(toSlot = slot + 1)
            else ranges += Range(kind, slot, slot + 1, pump, aaps)
        }
        return ranges
    }

    /** "HH:mm" of a slot (48 = "24:00"). */
    fun time(slot: Int): String = String.format(Locale.ROOT, "%02d:%02d", slot / 2, (slot % 2) * 30)

    private fun valueAt(profile: Profile, kind: Kind, seconds: Int, roundBasal: (Double) -> Double): Double = when (kind) {
        Kind.BASAL       -> roundBasal(profile.getBasalTimeFromMidnight(seconds))
        Kind.IC          -> profile.getIcTimeFromMidnight(seconds)
        Kind.ISF         -> profile.getIsfMgdlTimeFromMidnight(seconds)
        Kind.TARGET_LOW  -> profile.getTargetLowMgdlTimeFromMidnight(seconds)
        Kind.TARGET_HIGH -> profile.getTargetHighMgdlTimeFromMidnight(seconds)
    }
}
