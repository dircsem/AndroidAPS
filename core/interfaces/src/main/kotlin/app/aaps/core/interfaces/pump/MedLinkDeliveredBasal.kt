package app.aaps.core.interfaces.pump

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import kotlin.math.roundToInt

/**
 * MedLink: basal really delivered in the last [minutes], for the "Last 30 min" box on the main screen (every skin).
 *
 * @param rate delivered basal in U/h
 * @param percent of the profile basal in the same time (100 = no change)
 * @param minutes time used ([PILL_MINUTES] for the TBR pill / "Last 30 min" chip)
 */
data class MedLinkDeliveredBasal(val rate: Double, val percent: Int, val minutes: Int) {

    companion object {

        /**
         * Time of the TBR pill / "Last 30 min" chip: always 30 min (user, 2026-11-18). The MedLink setting
         * "Main screen TBR: minutes" changes only the blocks of the basal graph, not this value.
         */
        const val PILL_MINUTES = 30

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000.0

        /**
         * A MedLink temp basal above 100 % is TBR microboluses (no temp basal row) and below 100 % suspensions
         * (a 0 U/h temp basal row). So the delivered basal is: the basal line (temp basals and suspensions included)
         * plus the TBR microboluses, in the last [minutes].
         *
         * @return null when there is no profile for a part of the time
         */
        suspend fun calculate(
            now: Long,
            minutes: Int,
            profileFunction: ProfileFunction,
            iobCobCalculator: IobCobCalculator,
            persistenceLayer: PersistenceLayer
        ): MedLinkDeliveredBasal? {
            if (minutes <= 0) return null
            val from = now - minutes * MINUTE_MS
            var basalUnits = 0.0
            var profileUnits = 0.0
            var time = from
            while (time < now) {
                val profile = profileFunction.getProfile(time) ?: return null
                val basalData = iobCobCalculator.getBasalData(profile, time)
                val rate = if (basalData.isTempBasalRunning) basalData.tempBasalAbsolute else basalData.basal
                val hours = minOf(MINUTE_MS, now - time) / HOUR_MS
                basalUnits += rate * hours
                profileUnits += basalData.basal * hours
                time += MINUTE_MS
            }
            val tbrBolusUnits = persistenceLayer.getBolusesFromTimeToTime(from, now, true)
                .filter { it.isValid && it.type == BS.Type.TBR }
                .sumOf { it.amount }
            val delivered = basalUnits + tbrBolusUnits
            val percent = if (profileUnits > 0.0) (delivered / profileUnits * 100).roundToInt() else 100
            return MedLinkDeliveredBasal(rate = delivered * 60.0 / minutes, percent = percent, minutes = minutes)
        }
    }
}
