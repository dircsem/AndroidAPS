package app.aaps.core.objects.overview

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Keeps dashboard / overview headline glucose aligned with the APS [GlucoseStatus] pipeline
 * (bucket head, smoothed when applicable) when a smoothing plugin opts in, instead of a transient
 * raw DB value from [app.aaps.core.interfaces.overview.LastBgData].
 */
object DashboardCoherentGlucose {

    /** Same freshness window as SMB / AutoISF glucose status calculators for non-stale data. */
    private const val GLUCOSE_STATUS_FRESH_MS = 7 * 60 * 1000L

    /**
     * Dashboard [Context] is often [Context.getApplicationContext] (launcher theme without BG attrs).
     * Wrapping with [AppTheme] ensures `bgInRange` / `bgLow` / `highColor` resolve instead of falling
     * back to white — which made the hybrid glucose hero unreadable in light mode.
     */
    @ColorInt
    private fun resolveDashboardThemeColor(context: Context?, rh: ResourceHelper, @AttrRes attr: Int): Int {
        val base = context ?: return rh.gac(attr)
        val themed = ContextThemeWrapper(base, app.aaps.core.ui.R.style.AppTheme)
        return rh.gac(themed, attr)
    }

    fun displayMgdl(
        lastBg: InMemoryGlucoseValue?,
        glucoseStatus: GlucoseStatus?,
        smoothing: Smoothing,
        now: Long
    ): Double? =
        if (smoothing.preferDashboardGlucoseFromGlucoseStatus() && isFreshGlucoseStatus(glucoseStatus, now)) {
            glucoseStatus!!.glucose
        } else {
            lastBg?.recalculated
        }

    fun displayTimestamp(
        lastBg: InMemoryGlucoseValue?,
        glucoseStatus: GlucoseStatus?,
        smoothing: Smoothing,
        now: Long
    ): Long? =
        if (smoothing.preferDashboardGlucoseFromGlucoseStatus() && isFreshGlucoseStatus(glucoseStatus, now)) {
            glucoseStatus!!.date
        } else {
            lastBg?.timestamp
        }

    fun isDisplayActual(
        lastBg: InMemoryGlucoseValue?,
        glucoseStatus: GlucoseStatus?,
        smoothing: Smoothing,
        now: Long,
        maxAgeMs: Long
    ): Boolean {
        val ts = displayTimestamp(lastBg, glucoseStatus, smoothing, now) ?: return false
        return ts > now - maxAgeMs
    }

    fun isDisplayLow(displayMgdl: Double?, profileFunction: ProfileFunction, preferences: Preferences): Boolean {
        if (displayMgdl == null) return false
        val units = profileFunction.getUnits()
        val v = if (units == GlucoseUnit.MGDL) displayMgdl else displayMgdl * Constants.MGDL_TO_MMOLL
        return v < preferences.get(UnitDoubleKey.OverviewLowMark)
    }

    fun isDisplayHigh(displayMgdl: Double?, profileFunction: ProfileFunction, preferences: Preferences): Boolean {
        if (displayMgdl == null) return false
        val units = profileFunction.getUnits()
        val v = if (units == GlucoseUnit.MGDL) displayMgdl else displayMgdl * Constants.MGDL_TO_MMOLL
        return v > preferences.get(UnitDoubleKey.OverviewHighMark)
    }

    @ColorInt
    fun displayBgColor(
        context: Context?,
        displayMgdl: Double?,
        profileFunction: ProfileFunction,
        preferences: Preferences,
        rh: ResourceHelper
    ): Int =
        when {
            isDisplayLow(displayMgdl, profileFunction, preferences) ->
                resolveDashboardThemeColor(context, rh, app.aaps.core.ui.R.attr.bgLow)
            isDisplayHigh(displayMgdl, profileFunction, preferences) ->
                resolveDashboardThemeColor(context, rh, app.aaps.core.ui.R.attr.highColor)
            else ->
                resolveDashboardThemeColor(context, rh, app.aaps.core.ui.R.attr.bgInRange)
        }

    fun displayBgDescription(
        displayMgdl: Double?,
        profileFunction: ProfileFunction,
        preferences: Preferences,
        rh: ResourceHelper
    ): String =
        when {
            isDisplayLow(displayMgdl, profileFunction, preferences) ->
                rh.gs(app.aaps.core.ui.R.string.a11y_low)
            isDisplayHigh(displayMgdl, profileFunction, preferences) ->
                rh.gs(app.aaps.core.ui.R.string.a11y_high)
            displayMgdl == null -> ""
            else ->
                rh.gs(app.aaps.core.ui.R.string.a11y_inrange)
        }

    private fun isFreshGlucoseStatus(glucoseStatus: GlucoseStatus?, now: Long): Boolean {
        val gs = glucoseStatus ?: return false
        return gs.date >= now - GLUCOSE_STATUS_FRESH_MS
    }
}
