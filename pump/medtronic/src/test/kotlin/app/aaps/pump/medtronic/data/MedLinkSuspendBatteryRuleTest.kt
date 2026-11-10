package app.aaps.pump.medtronic.data

import app.aaps.pump.medtronic.data.MedLinkSuspendBatteryRule.Device
import app.aaps.pump.medtronic.data.MedLinkSuspendBatteryRule.Low
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MedLinkSuspendBatteryRuleTest {

    @Test
    fun `both batteries above the minimum - suspension allowed`() {
        assertNull(MedLinkSuspendBatteryRule.low(phoneLevel = 80, medLinkLevel = 38, minimum = 5))
    }

    @Test
    fun `MedLink at or below the minimum - blocked`() {
        assertEquals(Low(Device.MEDLINK, 5), MedLinkSuspendBatteryRule.low(80, 5, 5))
        assertEquals(Low(Device.MEDLINK, 8), MedLinkSuspendBatteryRule.low(80, 8, 10))
    }

    @Test
    fun `phone at or below the minimum - blocked`() {
        assertEquals(Low(Device.PHONE, 4), MedLinkSuspendBatteryRule.low(4, 38, 5))
        assertNull(MedLinkSuspendBatteryRule.low(6, 38, 5))
    }

    @Test
    fun `both low - the MedLink is reported`() {
        assertEquals(Low(Device.MEDLINK, 3), MedLinkSuspendBatteryRule.low(4, 3, 5))
    }

    @Test
    fun `unknown levels do not block`() {
        assertNull(MedLinkSuspendBatteryRule.low(null, null, 5))
    }
}
