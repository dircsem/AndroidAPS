package app.aaps.pump.common.hw.medlink.bolus

import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * T14: a TBR microbolus and an SMB queued close to each other are sent to the pump as one bolus
 * (0.1 U + 0.4 U -> one 0.5 U), but recorded as one TBR and one SMB.
 *
 * T16: a manual bolus takes the queued TBR (and the SMB, when the user confirmed) into itself, so
 * the pump gets one bolus with manual + TBR (+ SMB). The manual bolus stays part 0 (filled first,
 * MANUAL origin in the tracker).
 *
 * Rules:
 *  - a TBR / SMB merges into a queued loop bolus, and a TBR into a queued manual bolus;
 *  - an SMB never merges into a manual bolus by itself: only [canAbsorbIntoManual] with the user's consent;
 *  - at most one part of each type: one TBR, one SMB, one manual;
 *  - only into a bolus the pump has not received: never posted, tracked but the dose not written yet,
 *    or proved not delivered by `M` before a re-send (4i, [canJoinTracked]), see MedLinkBLE;
 *  - the delivered amount is split over the parts in queue order, the last part takes the rest;
 *  - part i is recorded at pump time + i * [PART_OFFSET_MS]: the DB temporary id comes from the
 *    timestamp, so equal times would drop the second part. The offset stays inside the pump minute,
 *    so the single bolus read later from the pump history maps onto part 0 and is not added again.
 */
object LoopBolusMerge {

    const val PART_OFFSET_MS = 1_000L

    private val loopTypes = setOf(MedLinkCommandType.TBRBolus, MedLinkCommandType.SMBBolus)
    private val mergeableTypes = loopTypes + MedLinkCommandType.Bolus

    fun isLoopBolus(type: MedLinkCommandType?): Boolean = type in loopTypes

    /** May a bolus of [newType] be folded into a queued bolus made of [partTypes]? */
    fun canMerge(partTypes: List<MedLinkCommandType>, newType: MedLinkCommandType): Boolean =
        isLoopBolus(newType) && partTypes.isNotEmpty() && partTypes.all { it in mergeableTypes } && newType !in partTypes &&
            !(newType == MedLinkCommandType.SMBBolus && MedLinkCommandType.Bolus in partTypes)

    /**
     * 4i: may a bolus of [newType] join the tracked bolus made of [partTypes] that the pump has not received:
     * not written yet (QUEUED), or proved not delivered by `M` and about to be re-sent.
     * Any type may join, at most one of each; SMB and manual together only with the user's consent (T16).
     */
    fun canJoinTracked(partTypes: List<MedLinkCommandType>, newType: MedLinkCommandType, smbConsent: Boolean): Boolean =
        newType in mergeableTypes && partTypes.isNotEmpty() && partTypes.all { it in mergeableTypes } && newType !in partTypes &&
            when (newType) {
                MedLinkCommandType.SMBBolus -> MedLinkCommandType.Bolus !in partTypes || smbConsent
                MedLinkCommandType.Bolus    -> MedLinkCommandType.SMBBolus !in partTypes || smbConsent
                else                        -> true
            }

    /** T16: may a queued loop bolus part of [type] be taken into a manual bolus made of [partTypes]? */
    fun canAbsorbIntoManual(partTypes: List<MedLinkCommandType>, type: MedLinkCommandType, smbConsent: Boolean): Boolean =
        isLoopBolus(type) && type !in partTypes && (type != MedLinkCommandType.SMBBolus || smbConsent)

    /** Amount sent to the pump for the parts (rounded to 0.01 U, as written in the BOLUS command). */
    fun total(requested: List<Double>): Double = round(requested.sum(), 100.0)

    /** Delivered amount of each part, in queue order. Sum equals [delivered]. */
    fun split(requested: List<Double>, delivered: Double): List<Double> {
        if (requested.isEmpty()) return emptyList()
        var left = max(0.0, delivered)
        return requested.mapIndexed { i, r ->
            val part = if (i == requested.lastIndex) left else min(left, max(0.0, r))
            left = round(left - part, 1000.0)
            round(part, 1000.0)
        }
    }

    fun partTime(pumpTime: Long, index: Int): Long = pumpTime + index * PART_OFFSET_MS

    private fun round(value: Double, scale: Double): Double = (value * scale).roundToLong() / scale
}
