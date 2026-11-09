package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.pump.common.hw.medlink.ble.CommandExecutor
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.bolus.BolusStatusSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BleBolusCommandsTest {

    private val Fixtures_now = java.text.SimpleDateFormat("dd-MM-yy HH:mm", java.util.Locale.US).parse("09-11-26 13:31")!!.time

    private val logger: AAPSLogger = mock()
    private val plugin: MedLinkPumpPluginBase = mock()
    private val status: MedLinkPumpStatus = mock()
    private lateinit var ble: MedLinkBLE
    private val bolus = DetailedBolusInfo().also { it.insulin = 0.5 }

    @BeforeEach
    fun setup() {
        ble = mock()
        whenever(plugin.medLinkPumpStatus).thenReturn(status)
    }

    private fun bolusCommand() = BleBolusCommand(logger, null, plugin, bolus, true)

    // ---- BleBolusCommand: blind re-send paths removed (T7) ----------------------------------

    @Test
    fun `pump is bolusing - verify with M, never reset (re-send) the bolus`() {
        val executor: CommandExecutor<*> = mock()
        whenever(ble.currentCommand).thenReturn(executor)
        whenever(executor.getCurrentCommand()).thenReturn(app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType.SMBBolus)
        whenever(ble.isBolus(any())).thenReturn(true)
        bolusCommand().characteristicChanged("pump is bolusing", ble, "")
        verify(ble).onBolusUnclear()
    }

    @Test
    fun `pump suspended before the dose was written - re-deliver after start`() {
        whenever(ble.onBolusRejectedBeforeWrite()).thenReturn(true)
        bolusCommand().characteristicChanged("pump suspend state", ble, "")
        verify(plugin).setSuspended()
        verify(plugin).reDeliverBolus(bolus)
    }

    @Test
    fun `pump suspended after the dose was written - verify with M, no re-delivery`() {
        whenever(ble.onBolusRejectedBeforeWrite()).thenReturn(false)
        bolusCommand().characteristicChanged("pump suspend state", ble, "")
        verify(plugin).setSuspended()
        verify(plugin, never()).reDeliverBolus(any())
        verify(ble).onBolusUnclear()
    }

    @Test
    fun `the whole bolus answer goes to the tracker at ready, a chunk alone is not an M answer`() {
        // real field answer (failed bolus), as 20-byte BLE chunks
        val chunks = listOf("bolus verification\n", "check pump status\n", "pump normal state\n", "last bolus: 2.0u 09-", "11-26 13:22\nre", "ady\n")
        val cmd = bolusCommand()
        var last = ""
        chunks.forEach { cmd.characteristicChanged(it, ble, last); last = it }
        verify(ble, never()).onBolusStatus(anyOrNull())
        val captor = argumentCaptor<String>()
        verify(ble).onBolusAnswer(captor.capture())
        val answer = app.aaps.pump.common.hw.medlink.bolus.BolusCommandAnswer.parse(captor.firstValue, Fixtures_now)!!
        assertEquals(2.0, answer.lastBolusAmount, 0.0001)
        assertEquals(false, answer.bolusing)
    }

    // ---- BleBolusStatusCommand (T5) ---------------------------------------------------------

    private fun feedStatus(vararg lines: String) {
        val executor: CommandExecutor<*> = mock()
        whenever(ble.currentCommand).thenReturn(executor)
        whenever(executor.hasFinished()).thenReturn(false)
        val cmd = BleBolusStatusCommand(logger, null, plugin)
        lines.forEach { cmd.characteristicChanged(it, ble, "") }
    }

    @Test
    fun `M answer is passed to the tracker and completes`() {
        feedStatus("m command confirmed", "last bolus: 0.5u 10-06-21 17:45", "square bolus: 0.0u delivered: 0.000u", "ready")
        val captor = argumentCaptor<BolusStatusSnapshot>()
        verify(ble).onBolusStatus(captor.capture())
        assertEquals(0.5, captor.firstValue.lastBolusAmount, 0.0001)
        verify(ble).completedCommand()
    }

    @Test
    fun `M answer with a bolus in progress completes, the tracker schedules the next read (no tight loop)`() {
        feedStatus("m command confirmed", "last bolus: 1.0u 10-06-21 17:45", "square bolus: 1.0u delivered: 0.400u", "ready")
        verify(ble).onBolusStatus(anyOrNull())
        verify(ble, never()).clearExecutedCommand()
        verify(ble).completedCommand()
    }

    @Test
    fun `MedLink powers down before the M answer - M is read again from the start`() {
        val executor: CommandExecutor<*> = mock()
        whenever(ble.currentCommand).thenReturn(executor)
        whenever(executor.hasFinished()).thenReturn(false)
        whenever(executor.isAwaitingAnswer()).thenReturn(true)
        BleBolusStatusCommand(logger, null, plugin).characteristicChanged("time to powerdown 5", ble, "")
        val order = org.mockito.kotlin.inOrder(ble)
        order.verify(ble).clearExecutedCommand()
        order.verify(ble).nextCommand()
    }

    @Test
    fun `answered M - powerdown only moves the queue on`() {
        val executor: CommandExecutor<*> = mock()
        whenever(ble.currentCommand).thenReturn(executor)
        whenever(executor.hasFinished()).thenReturn(true)
        BleBolusStatusCommand(logger, null, plugin).characteristicChanged("time to powerdown 5", ble, "")
        verify(ble, never()).clearExecutedCommand()
        verify(ble).nextCommand()
    }

    @Test
    fun `M answer without last bolus line reports unknown (null) to the tracker`() {
        feedStatus("m command confirmed", "last something", "ready")
        verify(ble).onBolusStatus(null)
    }
}
