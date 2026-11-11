package app.aaps.core.interfaces.pump

import app.aaps.core.interfaces.queue.Callback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * MedLink: the MedLink driver answers its commands through a [Callback], while AAPS 4.0 runs pump commands as
 * suspend functions that return the result. This waits for the callback.
 *
 * The wait is bounded: a callback that never comes would keep the command "performing" and block every later
 * command (the 4ae "pump is busy" lock). After [timeoutMs] the result of [onTimeout] is returned instead.
 */
object MedLinkCallbackBridge {

    /** Longest MedLink command observed is a bolus with its status reads (a few minutes); 10 min is well above it. */
    const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L

    /** For driver calls that answer through a [Callback]. */
    suspend fun await(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onTimeout: () -> PumpEnactResult,
        start: (Callback) -> Unit
    ): PumpEnactResult =
        awaitResult(timeoutMs, onTimeout) { done ->
            start(object : Callback() {
                override fun run() = done(result)
            })
        }

    /** For driver calls that answer through a function; only the first answer counts. */
    suspend fun awaitResult(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onTimeout: () -> PumpEnactResult,
        start: ((PumpEnactResult) -> Unit) -> Unit
    ): PumpEnactResult =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                start { result -> if (continuation.isActive) continuation.resume(result) }
            }
        } ?: onTimeout()
}
