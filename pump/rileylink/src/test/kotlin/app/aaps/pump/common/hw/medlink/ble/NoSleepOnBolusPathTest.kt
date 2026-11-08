package app.aaps.pump.common.hw.medlink.ble

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T11 (partial): the bolus / stop paths must not sleep on the BLE threads.
 * Remaining sleeps (BleCommand 700 ms, BleConnectCommand 500 ms, BleCalCommand, executor pacing,
 * medLinkConnect) are tracked for the P2 step and not covered here yet.
 */
class NoSleepOnBolusPathTest {

    private val base = "src/main/kotlin/app/aaps/pump/common/hw/medlink"
    private val sleep = Regex("""^(?!\s*//).*\b(SystemClock|Thread)\.sleep\(""", RegexOption.MULTILINE)

    private fun source(path: String) = File(base, path).readText()

    @Test
    fun `stop and bolus commands never sleep`() {
        listOf(
            "ble/command/BleStopCommand.kt",
            "ble/command/BleStartCommand.kt",
            "ble/command/BleBolusCommand.kt",
            "ble/command/BleBolusStatusCommand.kt",
            "activities/BolusProgressCallback.kt"
        ).forEach { assertTrue(!sleep.containsMatchIn(source(it)), "$it sleeps") }
    }

    @Test
    fun `MedLinkBLE has no 180 s GATT 133 sleep and no 5 s bolus spacing sleep`() {
        val ble = source("ble/MedLinkBLE.kt")
        assertTrue(!ble.contains("SystemClock.sleep(180000)"))
        assertTrue(!ble.contains("SystemClock.sleep(500)"))
        assertTrue(!ble.contains("Thread.sleep(5000)"))
    }
}
