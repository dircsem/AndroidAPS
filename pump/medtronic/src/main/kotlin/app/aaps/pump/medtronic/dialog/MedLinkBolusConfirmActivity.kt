package app.aaps.pump.medtronic.dialog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * T16: OK / Cancel question shown from the pump driver (background thread), e.g. "an SMB is in progress,
 * continue with the manual bolus?". The answer is delivered once; no answer within [TIMEOUT_MS],
 * back press or the activity going away count as Cancel.
 */
class MedLinkBolusConfirmActivity : AppCompatActivity() {

    companion object {

        const val TIMEOUT_MS = 2 * 60_000L
        private const val EXTRA_ID = "medlink_confirm_id"
        private const val EXTRA_TITLE = "medlink_confirm_title"
        private const val EXTRA_MESSAGE = "medlink_confirm_message"

        private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun ask(context: Context, title: String, message: String, onAnswer: (Boolean) -> Unit) {
            val id = UUID.randomUUID().toString()
            pending[id] = onAnswer
            mainHandler.postDelayed({ answer(id, false) }, TIMEOUT_MS)
            context.startActivity(
                Intent(context, MedLinkBolusConfirmActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_MESSAGE, message)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        private fun answer(id: String, ok: Boolean) {
            pending.remove(id)?.let { callback -> Thread { callback(ok) }.start() }
        }
    }

    private var id: String = ""
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_ID) ?: ""
        if (!pending.containsKey(id)) {
            finish()
            return
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(intent.getStringExtra(EXTRA_TITLE))
            .setMessage(intent.getStringExtra(EXTRA_MESSAGE))
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> close(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> close(false) }
            .show()
        // close the question together with the timeout
        mainHandler.postDelayed({ if (!pending.containsKey(id) && !isFinishing) finish() }, TIMEOUT_MS + 500)
    }

    private fun close(ok: Boolean) {
        answer(id, ok)
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        if (isFinishing) answer(id, false)
        super.onDestroy()
    }
}
