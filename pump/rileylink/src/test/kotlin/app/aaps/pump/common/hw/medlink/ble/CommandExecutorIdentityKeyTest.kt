package app.aaps.pump.common.hw.medlink.ble

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.common.hw.medlink.ble.command.BleCommand
import app.aaps.pump.common.hw.medlink.ble.data.CommandStructure
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.IdentityHashMap
import java.util.Optional

/**
 * 4t (`logs/tbr_manual_bolus.llog`): manual 4.0 U + TBR 0.1 U merged into one 4.1 U bolus were recorded as one
 * NORMAL bolus. The merged parts were in a HashMap keyed by the executor, and CommandExecutor.hashCode() changes
 * while the executor is written (commandPosition), so the parts were not found at record time.
 */
class CommandExecutorIdentityKeyTest {

    private val logger: AAPSLogger = mock()

    private fun bolusExecutor(): CommandExecutor<Any> =
        object : CommandExecutor<Any>(
            listOf(MedLinkCommandType.Bolus, MedLinkCommandType.BolusAmount)
                .map { CommandStructure<Any, BleCommand>(it, Optional.empty(), Optional.empty(), it.getRaw()) }.toMutableList(),
            logger
        ) {}

    @Test
    fun `hash of an executor changes while it is written`() {
        val e = bolusExecutor()
        val before = e.hashCode()
        e.commandExecuted()
        // this is why a HashMap keyed by an executor loses its entry
        assert(before != e.hashCode())
    }

    @Test
    fun `identity map keeps the merged parts after the executor is written`() {
        val e = bolusExecutor()
        val parts = IdentityHashMap<CommandExecutor<*>, List<String>>()
        parts[e] = listOf("NORMAL 4.0", "TBR 0.1")
        e.commandExecuted()
        e.commandExecuted()
        assertEquals(listOf("NORMAL 4.0", "TBR 0.1"), parts[e])
    }
}
