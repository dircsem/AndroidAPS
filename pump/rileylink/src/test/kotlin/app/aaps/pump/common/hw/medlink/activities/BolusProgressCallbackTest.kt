package app.aaps.pump.common.hw.medlink.activities

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.pump.common.hw.medlink.bolus.Fixtures
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date
import java.util.function.Supplier
import java.util.stream.Stream

/** Root cause 7: `delivered == 0.0` must not record a bolus that was never applied. */
class BolusProgressCallbackTest {

    private val rh: ResourceHelper = mock()
    private val rxBus: RxBus = mock()
    private val logger: AAPSLogger = mock()
    private val status: MedLinkPumpStatus = mock()
    private val pump: MedLinkPumpDevice = mock()
    private val bolus = DetailedBolusInfo().also { it.insulin = 0.5 }

    @BeforeEach
    fun setup() {
        whenever(pump.medLinkPumpStatus).thenReturn(status)
        whenever(rh.gs(any<Int>(), any())).thenReturn("")
        // last bolus known before sending: 0.5 U at 17:30
        whenever(status.lastBolusTime).thenReturn(Date(Fixtures.pumpTime("10-06-21 17:30")))
        whenever(status.lastBolusAmount).thenReturn(0.5)
    }

    private fun apply(vararg lines: String) {
        val callback = BolusProgressCallback(rh, rxBus, logger, pump, bolus)
        callback.apply(Supplier { Stream.of(*lines) })
    }

    @Test
    fun `square 0 with the old last bolus is not recorded`() {
        apply("m command confirmed", "last bolus: 0.5u 10-06-21 17:30", "square bolus: 0.0u delivered: 0.000u")
        verify(pump, never()).handleNewTreatmentData(any<DetailedBolusInfo>(), any())
    }

    @Test
    fun `square 0 with a new last bolus of the requested amount is recorded with the pump time`() {
        apply("m command confirmed", "last bolus: 0.5u 10-06-21 17:45", "square bolus: 0.0u delivered: 0.000u")
        val captor = argumentCaptor<DetailedBolusInfo>()
        verify(pump).handleNewTreatmentData(captor.capture(), eq(false))
        assertEquals(0.5, captor.firstValue.insulin, 0.0001)
        assertEquals(Fixtures.pumpTime("10-06-21 17:45"), captor.firstValue.timestamp)
    }

    @Test
    fun `bolus in progress is not recorded yet`() {
        apply("m command confirmed", "last bolus: 0.5u 10-06-21 17:45", "square bolus: 0.5u delivered: 0.200u")
        verify(pump, never()).handleNewTreatmentData(any<DetailedBolusInfo>(), any())
    }

    @Test
    fun `unparseable answer is not recorded`() {
        apply("m command confirmed", "time to powerdown")
        verify(pump, never()).handleNewTreatmentData(any<DetailedBolusInfo>(), any())
        verify(rxBus, never()).send(anyOrNull())
    }
}
