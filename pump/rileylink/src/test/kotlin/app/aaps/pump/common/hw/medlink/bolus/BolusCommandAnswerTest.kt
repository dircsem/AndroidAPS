package app.aaps.pump.common.hw.medlink.bolus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale

class BolusCommandAnswerTest {

    private fun t(text: String) = SimpleDateFormat("dd-MM-yy HH:mm", Locale.US).parse(text)!!.time
    private val now = t("09-11-26 13:31")

    @Test
    fun `failed bolus - normal state and the previous bolus with its time (logs failed_bolus_sample)`() {
        val text = "X command confirmed\nCheck pump status\nPump normal state\nSet bolus\nBOLUS command confirmed\nBolus verification\n" +
            "Send command for pump wakeup\nConfirmed pump wakeup\nCheck pump status\nPump normal state\nLast bolus: 2.0u 09-11-26 13:22\nReady"
        val a = BolusCommandAnswer.parse(text, now)!!
        assertEquals(2.0, a.lastBolusAmount, 1e-9)
        assertEquals(t("09-11-26 13:22"), a.lastBolusTime)
        assertFalse(a.bolusing)
    }

    @Test
    fun `delivering - pump is bolusing and last bolus without time (logs basal_problem 11-30-00)`() {
        val text = "x command confirmed\n\ncheck pump status\npump normal state\nbolus command confir\nmed\nbolus verification\n" +
            "send command for pump wakeup\nconfirmed pump wakeup\ncheck pump statuspump is bolusing state\nlast bolus: 0.4u\nready\n"
        val a = BolusCommandAnswer.parse(text, now)!!
        assertEquals(0.4, a.lastBolusAmount, 1e-9)
        assertNull(a.lastBolusTime)
        assertTrue(a.bolusing)
    }

    @Test
    fun `success sample - 0_4 U being applied, no time (logs bolus_success)`() {
        val text = "X\nX command confirmed \nCheck pump status \nPump normal state \nSet bolus \nBOLUS  0.4\nBOLUS command confirmed \n" +
            "Bolus verification \nSend command for pump wakeup \nConfirmed pump wakeup \nCheck pump status \nPump is bolusing state \nLast bolus: 0.4u \nReady"
        val a = BolusCommandAnswer.parse(text, now)!!
        assertEquals(0.4, a.lastBolusAmount, 1e-9)
        assertNull(a.lastBolusTime)
        assertTrue(a.bolusing)
    }

    @Test
    fun `year 00 from the MedLink is moved to the year of the read`() {
        val a = BolusCommandAnswer.parse("pump normal state\nlast bolus: 0.5u 09-11-00 11:03\nready", now)!!
        assertEquals(t("09-11-26 11:03"), a.lastBolusTime)
    }

    @Test
    fun `no last bolus line - no answer`() {
        assertNull(BolusCommandAnswer.parse("bolus command confirmed\nready", now))
    }
}
