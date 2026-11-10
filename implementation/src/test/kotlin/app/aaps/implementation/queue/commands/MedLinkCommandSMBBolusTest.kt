package app.aaps.implementation.queue.commands

import app.aaps.implementation.queue.commands.MedLinkCommandSMBBolus.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The user's rule (4q): the SMB interval counts from the last SMB or manual bolus; TBRs do not count. */
class MedLinkCommandSMBBolusTest {

    private val now = 1_762_000_000_000L
    private val min = 60_000L

    private fun decide(lastBolus: Long, deliverAt: Long = now, interval: Int = 3) = MedLinkCommandSMBBolus.decide(lastBolus, deliverAt, interval, now)

    @Test
    fun `SMB or manual bolus less than the interval ago - refused`() {
        assertThat(decide(now - 2 * min)).isEqualTo(Decision.IN_INTERVAL)
        assertThat(decide(now)).isEqualTo(Decision.IN_INTERVAL)
    }

    @Test
    fun `interval passed since the last SMB or manual bolus - delivered`() {
        assertThat(decide(now - 3 * min)).isEqualTo(Decision.DELIVER)
        assertThat(decide(now - 10 * min)).isEqualTo(Decision.DELIVER)
        assertThat(decide(0L)).isEqualTo(Decision.DELIVER)
    }

    @Test
    fun `a recent TBR does not block the SMB`() {
        // logs/samplelog.log 15:37:02: deliverAt (last TBR) == now; logs/smbbolus_fail.log 14:45:39: last TBR 4 min ago
        assertThat(decide(lastBolus = now - 10 * min, deliverAt = now)).isEqualTo(Decision.DELIVER)
        assertThat(decide(lastBolus = now - 10 * min, deliverAt = now - 4 * min)).isEqualTo(Decision.DELIVER)
    }

    @Test
    fun `request without deliverAt - canceled`() {
        assertThat(decide(lastBolus = 0L, deliverAt = 0L)).isEqualTo(Decision.NO_DELIVER_AT)
    }

    @Test
    fun `follows the configured SMB interval`() {
        assertThat(decide(now - 5 * min, interval = 3)).isEqualTo(Decision.DELIVER)
        assertThat(decide(now - 5 * min, interval = 6)).isEqualTo(Decision.IN_INTERVAL)
    }
}
