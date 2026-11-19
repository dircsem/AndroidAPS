package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.icons.IcPumpCartridge
import app.aaps.ui.compose.overview.statusLights.StatusItem
import app.aaps.ui.compose.overview.statusLights.StatusUiState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [buildGlassUiState] returns an early all-defaults [GlassUiState] when [StatusCardState] is
 * null (see its `if (status == null) return GlassUiState()` guard) — that guard is not touched by
 * this test. So a minimal, non-null [StatusCardState] is passed here to exercise the
 * lastBolusText/lastCarbsText "most recent valid" reduction instead.
 */
class GlassUiStateBuilderTest {

    private fun minimalStatus() = StatusCardState(
        glucoseText = "--",
        glucoseColor = 0,
        trendArrowRes = null,
        trendDescription = "",
        deltaText = "",
        iobText = "",
        iobTotalU = 0.0,
        cobText = "",
        loopStatusText = "",
        loopIsRunning = false,
        timeAgo = "",
        timeAgoDescription = "",
        isGlucoseActual = false,
        contentDescription = "",
    )

    private fun treatmentData(boluses: List<BolusGraphPoint> = emptyList(), carbs: List<CarbsGraphPoint> = emptyList()) =
        TreatmentGraphData(boluses = boluses, carbs = carbs, extendedBoluses = emptyList(), therapyEvents = emptyList())

    @Test
    fun `most recent valid bolus label is used, invalid and older ones are ignored`() {
        val data = treatmentData(
            boluses = listOf(
                BolusGraphPoint(timestamp = 1000L, amount = 1.0, bolusType = BolusType.NORMAL, isValid = true, label = "1.00 U"),
                BolusGraphPoint(timestamp = 3000L, amount = 2.0, bolusType = BolusType.NORMAL, isValid = false, label = "2.00 U (invalid)"),
                BolusGraphPoint(timestamp = 2000L, amount = 1.5, bolusType = BolusType.SMB, isValid = true, label = "1.50 U"),
            )
        )
        val state = buildGlassUiState(status = minimalStatus(), lights = null, treatmentData = data)
        assertThat(state.lastBolusText).isEqualTo("1.50 U")
        // T27: the time of the same bolus, not of the invalid newer one
        assertThat(state.lastBolusAtMs).isEqualTo(2000L)
    }

    @Test
    fun `no valid bolus or carbs falls back to placeholder`() {
        val state = buildGlassUiState(status = minimalStatus(), lights = null, treatmentData = treatmentData())
        assertThat(state.lastBolusText).isEqualTo("--")
        assertThat(state.lastBolusAtMs).isNull()
        assertThat(state.lastCarbsText).isEqualTo("--")
    }

    /** GLASS used to drop the CGM link state, so a reconnect showed nothing at all. */
    @Test
    fun `cgm link state and glucose time reach the glass state`() {
        val warmup = CgmWarmupStatus(active = true, phase = CgmWarmupStatus.Phase.RECONNECTING, remainingMs = null, endsAtEpochMs = null, message = null)
        val status = minimalStatus().copy(warmup = warmup, isGlucoseActual = false, glucoseTimestampMs = 123_000L)
        val state = buildGlassUiState(status = status, lights = null, treatmentData = treatmentData())
        assertThat(state.warmup).isEqualTo(warmup)
        assertThat(state.isGlucoseActual).isFalse()
        assertThat(state.glucoseTimestampMs).isEqualTo(123_000L)
    }

    // ---- T29: reservoir level pill, insulin age color ----

    private fun lights(level: String?, levelStatus: StatusLevel, ageStatus: StatusLevel = StatusLevel.NORMAL) = StatusUiState(
        insulinStatus = StatusItem(label = "Insulin", age = "2d", ageTimestamp = 42_000L, ageStatus = ageStatus, levelNumber = level, levelStatus = levelStatus, icon = IcPumpCartridge)
    )

    @Test
    fun `reservoir pill - level text and the warning color from the settings`() {
        for (status in listOf(StatusLevel.NORMAL, StatusLevel.WARNING, StatusLevel.CRITICAL)) {
            val state = buildGlassUiState(status = minimalStatus(), lights = lights("86", status), treatmentData = treatmentData())
            assertThat(state.reservoirLevelNumber).isEqualTo("86")
            assertThat(state.insulinChangedAtMs).isEqualTo(42_000L)
            assertThat(state.reservoirLevelStatus).isEqualTo(status)
        }
    }

    @Test
    fun `reservoir pill - unknown level - dashes and no color`() {
        val state = buildGlassUiState(status = minimalStatus(), lights = lights(null, StatusLevel.CRITICAL), treatmentData = treatmentData())
        assertThat(state.reservoirLevelNumber).isNull()
        assertThat(state.reservoirLevelStatus).isEqualTo(StatusLevel.UNSPECIFIED)
    }

    @Test
    fun `insulin age pill - the age color only, a low reservoir does not color it`() {
        val state = buildGlassUiState(status = minimalStatus(), lights = lights("5", StatusLevel.CRITICAL, ageStatus = StatusLevel.NORMAL), treatmentData = treatmentData())
        assertThat(state.insulinStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(state.reservoirLevelStatus).isEqualTo(StatusLevel.CRITICAL)
    }
}
