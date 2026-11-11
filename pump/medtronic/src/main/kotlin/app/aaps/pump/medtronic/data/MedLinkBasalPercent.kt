package app.aaps.pump.medtronic.data

import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.medtronic.data.dto.TempBasalMicroBolusPair.OperationType
import kotlin.math.roundToInt

/**
 * 4ad: basal percentages of the Medtronic tab (all relative to the base basal of the hour).
 * - pump: what the pump does now (0 % suspended, 100 % running);
 * - plan: what the remaining suspensions / TBR microboluses deliver from now to the end of the plan;
 * - hour: what was delivered since the start of the clock hour (running time x base basal + TBR microboluses).
 */
object MedLinkBasalPercent {

    private const val HOUR = 3_600_000.0

    data class Op(val at: Long, val type: OperationType, val dose: Double)

    fun pump(state: PumpRunningState): Int? = when (state) {
        PumpRunningState.Running                                       -> 100
        PumpRunningState.Suspended, PumpRunningState.TempBasalSuspended -> 0
        PumpRunningState.Unknow                                        -> null
    }

    /** Percent the plan delivers between [now] and [end]; null without a plan or a base basal. */
    fun plan(now: Long, end: Long, base: Double, suspendedNow: Boolean, ops: List<Op>): Int? {
        if (base <= 0.0 || end <= now || ops.isEmpty()) return null
        var running = !suspendedNow
        var t = now
        var insulin = 0.0
        for (op in ops.sortedBy { it.at }) {
            val at = op.at.coerceIn(now, end)
            if (running) insulin += base * (at - t) / HOUR
            t = at
            when (op.type) {
                OperationType.SUSPEND    -> running = false
                OperationType.REACTIVATE -> running = true
                OperationType.BOLUS      -> insulin += op.dose
            }
        }
        if (running) insulin += base * (end - t) / HOUR
        return (insulin / (base * (end - now) / HOUR) * 100).roundToInt()
    }

    /**
     * Percent delivered between [from] and [now]. Only the time with a known pump state counts (the timeline starts
     * empty after an app restart); null when there is no known time or base basal.
     */
    fun delivered(from: Long, now: Long, base: Double, timeline: List<Pair<Long, PumpRunningState>>, tbrBoluses: List<Pair<Long, Double>>): Int? {
        if (base <= 0.0 || now <= from) return null
        var knownMs = 0L
        var runningMs = 0L
        timeline.forEachIndexed { i, (at, state) ->
            val start = maxOf(at, from)
            val end = minOf(timeline.getOrNull(i + 1)?.first ?: now, now)
            if (end <= start) return@forEachIndexed
            when (pump(state)) {
                100  -> { knownMs += end - start; runningMs += end - start }
                0    -> knownMs += end - start
                else -> Unit
            }
        }
        if (knownMs <= 0L) return null
        val knownFrom = maxOf(from, timeline.firstOrNull()?.first ?: now)
        val insulin = base * runningMs / HOUR + tbrBoluses.filter { it.first in knownFrom..now }.sumOf { it.second }
        return (insulin / (base * knownMs / HOUR) * 100).roundToInt()
    }

    /** Pump running state changes, kept for [KEEP_MS] (in memory: empty after an app restart). */
    class Timeline(private val keepMs: Long = KEEP_MS) {

        private val entries = ArrayList<Pair<Long, PumpRunningState>>()

        @Synchronized fun record(at: Long, state: PumpRunningState) {
            if (entries.lastOrNull()?.second == state) return
            entries.add(at to state)
            // keep the last change before the cut: it is the state at the start of the kept period
            while (entries.size > 1 && entries[1].first < at - keepMs) entries.removeAt(0)
        }

        @Synchronized fun snapshot(): List<Pair<Long, PumpRunningState>> = entries.toList()
    }

    const val KEEP_MS = 2 * 3_600_000L
}
