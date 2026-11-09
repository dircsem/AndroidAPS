package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.MedLinkStopOrigin
import app.aaps.pump.common.hw.medlink.ble.CommandExecutor
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpAnswerState
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import org.mockito.kotlin.whenever
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpRunningState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class BleStopStartCommandTest {

    private val logger: AAPSLogger = mock()
    private val plugin: MedLinkPumpPluginBase = mock()
    private val ble: MedLinkBLE = mock()

    private fun stop(origin: MedLinkStopOrigin) = BleStopCommand(logger, null, plugin, origin)

    @Test
    fun `A answer bolusing records Bolusing, never Suspended (T2)`() {
        stop(MedLinkStopOrigin.AUTOMATIC).characteristicChanged("pump is bolusing sta", ble, "check pump status")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Bolusing)
        verify(ble, never()).setLastPumpStatus(MedLinkPumpRunningState.Suspended)
        verify(ble).onPumpAnswerState(MedLinkPumpAnswerState.BOLUSING)
    }

    @Test
    fun `stop recognises the full real bolusing answer (field logs 2026)`() {
        stop(MedLinkStopOrigin.AUTOMATIC).characteristicChanged("check pump statuspump is bolusing state", ble, "")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Bolusing)
        verify(ble).holdStop()
    }

    @Test
    fun `MedLinkPumpStateAnswer matches the real chunked bolusing answer, not unrelated text`() {
        org.junit.jupiter.api.Assertions.assertTrue(app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpStateAnswer.isBolusing("pump is bolusing sta"))
        org.junit.jupiter.api.Assertions.assertTrue(app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpStateAnswer.isBolusing("pump is bolusing state"))
        org.junit.jupiter.api.Assertions.assertFalse(app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpStateAnswer.isBolusing("pump normal state"))
        org.junit.jupiter.api.Assertions.assertFalse(app.aaps.pump.common.hw.medlink.bolus.MedLinkPumpStateAnswer.isBolusing("pump suspend state"))
    }

    @Test
    fun `automatic stop on bolusing is held, not retried (T1, T11)`() {
        stop(MedLinkStopOrigin.AUTOMATIC).characteristicChanged("pump is bolusing sta", ble, "check pump status")
        verify(ble).holdStop()
        verify(ble, never()).retryCommand()
        verify(ble, never()).completedCommand()
    }

    @Test
    fun `user stop on bolusing proceeds to STOP (T0)`() {
        stop(MedLinkStopOrigin.USER).characteristicChanged("pump is bolusing sta", ble, "check pump status")
        verify(ble, never()).holdStop()
        verify(ble).completedCommand()
    }

    @Test
    fun `suspend answer tells the tracker so a partial bolus is read (T3)`() {
        stop(MedLinkStopOrigin.USER).characteristicChanged("pump suspend state", ble, "")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Suspended)
        verify(ble).onPumpAnswerState(MedLinkPumpAnswerState.SUSPENDED)
        verify(ble).completedCommand(eq(true), eq(true))
    }

    @Test
    fun `normal answer records Running`() {
        stop(MedLinkStopOrigin.AUTOMATIC).characteristicChanged("pump normal state", ble, "")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Running)
        verify(ble).onPumpAnswerState(MedLinkPumpAnswerState.RUNNING)
    }

    @Test
    fun `stop command defaults to automatic origin`() {
        val cmd = BleStopCommand(logger, null, plugin)
        org.junit.jupiter.api.Assertions.assertEquals(MedLinkStopOrigin.AUTOMATIC, cmd.origin)
    }

    private fun executorAt(next: MedLinkCommandType): CommandExecutor<*> = mock<CommandExecutor<Any>>().also {
        whenever(it.nextCommand()).thenReturn(next)
    }

    @Test
    fun `start - suspend answer to A sends START next (T13)`() {
        val executor = executorAt(MedLinkCommandType.StartPump)
        whenever(ble.currentCommand).thenReturn(executor)
        BleStartCommand(logger, null, plugin).characteristicChanged("pump suspend state", ble, "")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Suspended)
        verify(ble).completedCommand()
        verify(ble, never()).onStartFailed(any())
    }

    @Test
    fun `start - suspend answer after START was written is a refused start, not a silent completion (T13, bug 4)`() {
        val executor = executorAt(MedLinkCommandType.NoCommand)
        whenever(ble.currentCommand).thenReturn(executor)
        BleStartCommand(logger, null, plugin).characteristicChanged("pump suspend state", ble, "")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Suspended)
        verify(ble).onStartFailed(any())
        verify(ble, never()).completedCommand()
    }

    @Test
    fun `start command on bolusing answer records Bolusing, not Running (T2)`() {
        BleStartCommand(logger, null, plugin).characteristicChanged("pump is bolusing st", ble, "")
        verify(ble).setLastPumpStatus(MedLinkPumpRunningState.Bolusing)
        verify(ble, never()).setLastPumpStatus(MedLinkPumpRunningState.Running)
        verify(plugin).cancelTempBasal(any(), any(), anyOrNull())
    }
}
