package app.aaps.plugins.main.general.dashboard.glass

/**
 * T17: every pill the Glass card can show, in the fixed display order (user, 2026-11-14): pump group first, then
 * CGM / loop, then the others, Tools last. The user always finds a pill near the same place.
 * [BATTERY_PAIR] = pump and MedLink battery in ONE pill (user, 2026-11-14). Tools is drawn as a small pill.
 */
enum class GlassSlot {
    RESERVOIR, CANNULA, BATTERY, BATTERY_PAIR, TBR, DELIVERED,
    SENSOR, LOOP, ACTIVITY,
    TARGET, LAST_BOLUS, IOB, LAST_CARBS,
    TOOLS
}

/**
 * Where each shown pill goes: [left] and [right] beside the glucose ring (top-down), then full [rows] below it.
 */
data class GlassPillLayout(val left: List<GlassSlot>, val right: List<GlassSlot>, val rows: List<List<GlassSlot>>)

/** Pills that fit beside the ring in one column (the ring is about 3 pill rows tall). */
const val GLASS_SLOTS_BESIDE_RING = 3

/** Pills in one row below the ring (the same 3 columns as the top: left, under the ring, right). */
const val GLASS_PILLS_PER_ROW = 3

/**
 * The pills really shown, in display order: the user's selection ([GlassPillId], `GlassSelectedPills`), plus the pills
 * that exist only in some cases (basal delivered with the TBR), plus Tools. With a MedLink battery the pump battery
 * pill becomes [GlassSlot.BATTERY_PAIR].
 */
fun shownGlassSlots(selected: Collection<GlassPillId>, hasMedLinkBattery: Boolean, hasDelivered: Boolean): List<GlassSlot> =
    GlassSlot.entries.filter { slot ->
        when (slot) {
            GlassSlot.RESERVOIR       -> GlassPillId.PUMP_RESERVOIR in selected
            GlassSlot.CANNULA         -> GlassPillId.CANNULA in selected
            GlassSlot.BATTERY         -> GlassPillId.BATTERY in selected && !hasMedLinkBattery
            GlassSlot.BATTERY_PAIR    -> GlassPillId.BATTERY in selected && hasMedLinkBattery
            GlassSlot.TBR             -> GlassPillId.BASAL_RATE in selected
            GlassSlot.DELIVERED       -> GlassPillId.BASAL_RATE in selected && hasDelivered
            GlassSlot.SENSOR          -> GlassPillId.SENSOR in selected
            GlassSlot.LOOP            -> GlassPillId.LOOP_STATUS in selected
            GlassSlot.ACTIVITY        -> GlassPillId.ACTIVITY in selected
            GlassSlot.TARGET          -> GlassPillId.TARGET in selected
            GlassSlot.LAST_BOLUS      -> GlassPillId.LAST_BOLUS in selected
            GlassSlot.IOB             -> GlassPillId.IOB in selected
            GlassSlot.LAST_CARBS      -> GlassPillId.LAST_CARBS in selected
            GlassSlot.TOOLS           -> true
        }
    }

/**
 * Fills top-down with the least height: first the slots beside the ring (left column, then right column; the left one
 * gets the extra pill, so the columns differ by at most one), then full rows below. No holes: a pill is never placed
 * after an empty slot. The order of [shown] is kept.
 */
fun layoutGlassPills(
    shown: List<GlassSlot>,
    slotsBesideRing: Int = GLASS_SLOTS_BESIDE_RING,
    pillsPerRow: Int = GLASS_PILLS_PER_ROW
): GlassPillLayout {
    val beside = shown.take(slotsBesideRing * 2)
    val leftCount = (beside.size + 1) / 2
    return GlassPillLayout(
        left = beside.take(leftCount),
        right = beside.drop(leftCount),
        rows = shown.drop(beside.size).chunked(pillsPerRow.coerceAtLeast(1))
    )
}
