package app.aaps.pump.common.hw.medlink.ble

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.common.hw.medlink.ble.command.BleCommand
import app.aaps.pump.common.hw.medlink.ble.data.CommandStructure
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.Optional

/**
 * Bug: after `m command confirmed` the M executor has no command left, but it was posted again
 * before its answer ("ready") arrived and wrote an empty payload -> MedLink "invalid command".
 */
class CommandExecutorPostTest {

    private val logger: AAPSLogger = mock()

    private fun executor(vararg commands: MedLinkCommandType): CommandExecutor<Any> =
        object : CommandExecutor<Any>(
            commands.map { CommandStructure<Any, BleCommand>(it, Optional.empty(), Optional.empty(), it.getRaw()) }.toMutableList(),
            logger
        ) {}

    @Test
    fun `fresh M has its payload to write`() {
        val m = executor(MedLinkCommandType.BolusStatus)
        assertArrayEquals(MedLinkCommandType.BolusStatus.getRaw(), m.dataToWrite())
        assertFalse(m.isAwaitingAnswer())
    }

    @Test
    fun `confirmed M has nothing left to write and waits for its answer`() {
        val m = executor(MedLinkCommandType.BolusStatus)
        m.commandExecuted()
        assertNull(m.dataToWrite())
        assertTrue(m.isAwaitingAnswer())
    }

    @Test
    fun `answered M is not waiting any more`() {
        val m = executor(MedLinkCommandType.BolusStatus)
        m.commandExecuted()
        m.applyCommand()
        assertFalse(m.isAwaitingAnswer())
        assertTrue(m.hasFinished())
    }

    @Test
    fun `a NoCommand step is never written`() {
        val e = executor(MedLinkCommandType.BolusStatus, MedLinkCommandType.NoCommand)
        e.commandExecuted()
        assertNull(e.dataToWrite())
    }

    @Test
    fun `two step command still writes its second step`() {
        val e = executor(MedLinkCommandType.StopStartPump, MedLinkCommandType.StopPump)
        e.commandExecuted()
        assertArrayEquals(MedLinkCommandType.StopPump.getRaw(), e.dataToWrite())
        assertFalse(e.isAwaitingAnswer())
    }

    @Test
    fun `current command waiting for its answer is not posted again`() {
        val m = executor(MedLinkCommandType.BolusStatus)
        m.commandExecuted()
        assertEquals(CommandExecutor.PostDecision.WAIT_FOR_ANSWER, m.postDecision(current = m))
    }

    @Test
    fun `a fully written command that is no longer current was completed and is dropped`() {
        val m = executor(MedLinkCommandType.BolusStatus)
        m.commandExecuted()
        assertEquals(CommandExecutor.PostDecision.DROP_COMPLETED, m.postDecision(current = null))
    }

    @Test
    fun `RemoveStopCommand marker has no payload and is dropped, never written`() {
        val marker = executor(MedLinkCommandType.RemoveStopCommand)
        assertNull(marker.dataToWrite())
        assertFalse(marker.isAwaitingAnswer())
        assertEquals(CommandExecutor.PostDecision.DROP_COMPLETED, marker.postDecision(current = marker))
        assertEquals(CommandExecutor.PostDecision.DROP_COMPLETED, marker.postDecision(current = null))
    }

    @Test
    fun `MedLinkBLE writes only a checked payload and the picker applies the post rule`() {
        val ble = java.io.File("src/main/kotlin/app/aaps/pump/common/hw/medlink/ble/MedLinkBLE.kt").readText()
        assertFalse(ble.contains("chara.value = nextCommandData()"), "regular executor writes before checking the payload")
        assertTrue(ble.contains("postDecision(currentCommand)"))
    }

    @Test
    fun `command with something to write is posted`() {
        val m = executor(MedLinkCommandType.BolusStatus)
        assertEquals(CommandExecutor.PostDecision.POST, m.postDecision(current = null))
        assertEquals(CommandExecutor.PostDecision.POST, m.postDecision(current = m))
        m.commandExecuted()
        m.clearExecutedCommand()
        assertEquals(CommandExecutor.PostDecision.POST, m.postDecision(current = m))
    }
}
