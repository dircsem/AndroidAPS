package app.aaps.pump.medtronic

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * T20: blinks the back camera flashlight during the "pump not reached" alarm. No permission is needed for
 * [CameraManager.setTorchMode] (API 23+). The torch is ALWAYS turned off at the end: in `finally`, on [stop], and
 * after [maxMs] at the latest (the AAPS full screen alarm has no OK callback, so a time limit is the guard).
 * No flash, or the camera in use: the light is skipped and logged; the sound and the notification still come.
 */
class MedLinkTorchBlinker(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val scope: CoroutineScope
) {

    private var job: Job? = null

    @Synchronized
    fun start(maxMs: Long = MAX_BLINK_MS) {
        stop()
        val cameraManager = context.getSystemService(CameraManager::class.java) ?: return
        val cameraId = try {
            flashCamera(cameraManager)
        } catch (e: CameraAccessException) {
            aapsLogger.warn(LTag.PUMP, "MedLink alarm: no flashlight (camera not available: ${e.message})")
            null
        } ?: run {
            aapsLogger.info(LTag.PUMP, "MedLink alarm: no flashlight on this phone, skipped")
            return
        }
        job = scope.launch {
            try {
                val end = System.currentTimeMillis() + maxMs
                var on = false
                while (isActive && System.currentTimeMillis() < end) {
                    on = !on
                    cameraManager.setTorchMode(cameraId, on)
                    delay(BLINK_MS)
                }
            } catch (e: CameraAccessException) {
                aapsLogger.warn(LTag.PUMP, "MedLink alarm: flashlight not available (${e.message}), skipped")
            } catch (e: IllegalArgumentException) {
                aapsLogger.warn(LTag.PUMP, "MedLink alarm: flashlight not available (${e.message}), skipped")
            } finally {
                try {
                    cameraManager.setTorchMode(cameraId, false)
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.PUMP, "MedLink alarm: could not turn the flashlight off (${e.message})")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun flashCamera(cameraManager: CameraManager): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

    companion object {

        const val BLINK_MS = 500L
        const val MAX_BLINK_MS = 2 * 60_000L
    }
}
