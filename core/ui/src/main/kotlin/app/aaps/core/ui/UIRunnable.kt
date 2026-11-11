package app.aaps.core.ui

import android.os.Handler
import android.os.Looper

/** MedLink: removed upstream in AAPS 4.0, still used by the MedLink fragment. */
class UIRunnable (val runnable: Runnable) : Runnable {
    private fun runOnUiThread(theRunnable: Runnable?) = theRunnable?.let {
        Handler(Looper.getMainLooper()).post(it)
    }

    override fun run() {
        runOnUiThread(runnable)
    }
}