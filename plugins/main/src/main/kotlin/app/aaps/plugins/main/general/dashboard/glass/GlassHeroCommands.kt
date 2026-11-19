package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.runtime.compositionLocalOf

interface GlassHeroCommands {
    fun openLoop()

    /** T22: long press on the BG circle (a tap opens the loop dashboard, as on the loop pill). Opens the Glass running mode screen (loop modes, suspend, pump disconnect), behind the BOLUS protection. */
    fun openRunningMode()
    fun openLoopDashboard()
    fun openTarget()
    fun openInsulin()
    fun openPump()
    fun openCannula()
    fun openBattery()
    fun openBasal()
    fun openSensorInsert()
    fun openSensorQuality()
    fun openStatsScreen()
    fun openTreatmentsScreen()
}

object NoopGlassHeroCommands : GlassHeroCommands {
    override fun openLoop() {}
    override fun openRunningMode() {}
    override fun openLoopDashboard() {}
    override fun openTarget() {}
    override fun openInsulin() {}
    override fun openPump() {}
    override fun openCannula() {}
    override fun openBattery() {}
    override fun openBasal() {}
    override fun openSensorInsert() {}
    override fun openSensorQuality() {}
    override fun openStatsScreen() {}
    override fun openTreatmentsScreen() {}
}

val LocalGlassHeroCommands = compositionLocalOf<GlassHeroCommands> { NoopGlassHeroCommands }
