package app.aaps.implementation.queue

import app.aaps.core.data.configuration.Constants
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** `logs/tbr_manual_bolus.llog` 08:22:35: "timed out" / "no connection possible" while the MedLink was reconnecting. */
class QueueWorkerConnectionTimeTest {

    private val workStart = 1_000_000L
    private val now = workStart + 10 * 60_000L // the work has been "waiting for disconnect" for 10 min

    private fun timedOut(start: Long) = (now - start) / 1000 > Constants.PUMP_MAX_CONNECTION_TIME_IN_SECONDS

    @Test
    fun `medlink connected - the timeout starts again`() {
        assertThat(QueueWorker.connectionStartFor(workStart, now, medLink = true, connected = true)).isEqualTo(now)
    }

    @Test
    fun `medlink reconnecting right after being connected - no timeout`() {
        // connected one second ago, reconnecting now: the timeout counts from the last connected moment
        val lastConnected = QueueWorker.connectionStartFor(workStart, now - 1_000, medLink = true, connected = true)
        val start = QueueWorker.connectionStartFor(lastConnected, now, medLink = true, connected = false)
        assertThat(timedOut(start)).isFalse()
    }

    @Test
    fun `medlink not connected for longer than the limit - timeout`() {
        val lastConnected = now - (Constants.PUMP_MAX_CONNECTION_TIME_IN_SECONDS + 1) * 1000L
        assertThat(timedOut(QueueWorker.connectionStartFor(lastConnected, now, medLink = true, connected = false))).isTrue()
    }

    @Test
    fun `other pumps keep counting from the start of the work`() {
        assertThat(QueueWorker.connectionStartFor(workStart, now, medLink = false, connected = true)).isEqualTo(workStart)
        assertThat(QueueWorker.connectionStartFor(workStart, now, medLink = false, connected = false)).isEqualTo(workStart)
    }
}
