package app.aaps.pump.medtronic.data

/**
 * 4x: minimum basal after a manual bolus. Veo temp basals are suspensions + microboluses; right after a manual bolus
 * the loop often asks for a low or zero temp basal while BG is still rising. Inside the window after the last manual
 * bolus, with BG at or above target and the last 5 min delta above the configured one, a lower temp basal is raised to
 * [Settings.minPercent] of the pump basal (a zero temp basal too). SMB and TBR microboluses do not start the window.
 */
object MedLinkPostBolusMinBasal {

    data class Settings(val minPercent: Int, val windowMinutes: Int, val minDelta: Double)

    data class Decision(val rate: Double, val reason: String)

    fun decide(
        requestedRate: Double,
        baseRate: Double,
        settings: Settings,
        lastManualBolusAt: Long?,
        now: Long,
        delta: Double?,
        bg: Double?,
        targetMgdl: Double
    ): Decision {
        if (settings.minPercent <= 0) return Decision(requestedRate, "off")
        if (baseRate <= 0.0) return Decision(requestedRate, "no pump basal")
        if (lastManualBolusAt == null || now - lastManualBolusAt > settings.windowMinutes * 60_000L || now < lastManualBolusAt)
            return Decision(requestedRate, "no manual bolus in the last ${settings.windowMinutes} min")
        if (bg == null || delta == null) return Decision(requestedRate, "no glucose data")
        if (bg < targetMgdl) return Decision(requestedRate, "BG $bg below target $targetMgdl")
        if (delta <= settings.minDelta) return Decision(requestedRate, "delta $delta not above ${settings.minDelta}")
        val minRate = baseRate * settings.minPercent / 100.0
        if (requestedRate >= minRate) return Decision(requestedRate, "requested at or above the minimum")
        return Decision(minRate, "raised to ${settings.minPercent}% of $baseRate U/h (delta $delta, BG $bg)")
    }
}
