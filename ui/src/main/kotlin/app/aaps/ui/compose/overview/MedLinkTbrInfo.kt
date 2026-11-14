package app.aaps.ui.compose.overview

import app.aaps.core.interfaces.pump.MedLinkDeliveredBasal
import app.aaps.core.interfaces.pump.MedLinkRunningTempBasal

/**
 * MedLink only: values for the TBR text next to the TBR chip on the classic main screen.
 * Null for other pumps (the chip stays icon only). Keep this when merging upstream.
 *
 * @param running the temp basal running now (null = no MedLink temp basal plan)
 * @param delivered basal delivered in the last minutes ("Last 30 min" chip)
 */
data class MedLinkTbrInfo(
    val running: MedLinkRunningTempBasal?,
    val delivered: MedLinkDeliveredBasal?
)
