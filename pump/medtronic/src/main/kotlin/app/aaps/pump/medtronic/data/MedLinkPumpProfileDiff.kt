package app.aaps.pump.medtronic.data

import app.aaps.core.interfaces.profile.Profile
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
    fun differences(pump: Profile, aaps: Profile, roundBasal: (Double) -> Double): List<Range> {
        val pumpValues = values(pump, roundBasal)
        val aapsValues = values(aaps, roundBasal)
        return Kind.entries.flatMap { kind -> differences(kind, pumpValues.getValue(kind), aapsValues.getValue(kind)) }
    }

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
