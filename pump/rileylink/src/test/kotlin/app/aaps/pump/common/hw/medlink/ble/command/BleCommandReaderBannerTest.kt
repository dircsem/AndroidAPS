package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.MedLinkStopOrigin
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * B16 (field log `invalid_command.log`, 2026-11-17 08:20:30 and 08:23:01): the wake-up banner "medtronic veo" was still
 * the previous chunk when the first chunk of the `A` answer came. The reader saw "medtronic" again and set
 * "not connected", so a second OK+CONN was written inside the STOP / START: "invalid command" for it and for the
 * STOP / START after it. The real chunks of the log are used here.
 */
class BleCommandReaderBannerTest {

    private val logger: AAPSLogger = mock()
    private val plugin: MedLinkPumpPluginBase = mock()
    private val ble: MedLinkBLE = mock()

    private fun reader() = BleCommandReader(logger, null, plugin)

    @Test
    fun `wake-up banner - not connected until ready, as before`() {
        reader().characteristicChanged("medtronic veo\nready", ble, "p\n")
        verify(ble).setIsConnected(false)
    }

    @Test
    fun `banner split in two chunks - still seen once`() {
        reader().characteristicChanged("onic veo\nready", ble, "confirmed pump wakeup, medtr")
        verify(ble).setIsConnected(false)
    }

    @Test
    fun `field case - first A chunk after the banner does not set not connected`() {
        reader().characteristicChanged("a command confirmed", ble, "medtronic veo\nready")
        verify(ble, never()).setIsConnected(false)
    }

    @Test
    fun `field case through the STOP command - no second OK+CONN inside the STOP`() {
        BleStopCommand(logger, null, plugin, MedLinkStopOrigin.AUTOMATIC).characteristicChanged("a command confirmed", ble, "medtronic veo\nready")
        verify(ble, never()).setIsConnected(false)
    }

    @Test
    fun `field case through the START command`() {
        BleStartCommand(logger, null, plugin).characteristicChanged("a command confirmed", ble, "medtronic veo\nready")
        verify(ble, never()).setIsConnected(false)
    }
}
