package app.aaps.pump.medtronic.dialog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.aaps.pump.common.hw.medlink.bolus.ManualBolusRule
import app.aaps.pump.medtronic.R
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * T16: question shown from the pump driver (background thread), e.g. "an SMB of X U is in progress,
 * continue with the manual bolus?". With [askAmount] the user may also lower the manual bolus amount.
 * The answer is delivered once; no answer within [TIMEOUT_MS], back press or the activity going away
 * count as Cancel (null amount).
 */
class MedLinkBolusConfirmActivity : AppCompatActivity() {

    companion object {

        const val TIMEOUT_MS = 2 * 60_000L
        private const val EXTRA_ID = "medlink_confirm_id"
        private const val EXTRA_TITLE = "medlink_confirm_title"
        private const val EXTRA_MESSAGE = "medlink_confirm_message"
        private const val EXTRA_AMOUNT = "medlink_confirm_amount"
        private const val EXTRA_STEP = "medlink_confirm_step"

        /** Answer: null = cancel, otherwise the amount (the requested one when not editable). */
        private val pending = ConcurrentHashMap<String, (Double?) -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun ask(context: Context, title: String, message: String, onAnswer: (Boolean) -> Unit) =
            start(context, title, message, null, 0.0) { onAnswer(it != null) }

        /** OK / Cancel with an editable amount between one [step] and [amount] (only lower: [amount] is within the limits). */
        fun askAmount(context: Context, title: String, message: String, amount: Double, step: Double, onAnswer: (Double?) -> Unit) =
            start(context, title, message, amount, step, onAnswer)

        private fun start(context: Context, title: String, message: String, amount: Double?, step: Double, onAnswer: (Double?) -> Unit) {
            val id = UUID.randomUUID().toString()
            pending[id] = onAnswer
            mainHandler.postDelayed({ answer(id, null) }, TIMEOUT_MS)
            context.startActivity(
                Intent(context, MedLinkBolusConfirmActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_MESSAGE, message)
                    .apply { if (amount != null) putExtra(EXTRA_AMOUNT, amount).putExtra(EXTRA_STEP, step) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        private fun answer(id: String, amount: Double?) {
            pending.remove(id)?.let { callback -> Thread { callback(amount) }.start() }
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
        val requested = if (intent.hasExtra(EXTRA_AMOUNT)) intent.getDoubleExtra(EXTRA_AMOUNT, 0.0) else null
        val step = intent.getDoubleExtra(EXTRA_STEP, 0.1)
        val input = requested?.let {
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(Locale.US, "%.2f", it))
                hint = getString(R.string.medlink_smb_pending_amount_hint)
                setSelectAllOnFocus(true)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(intent.getStringExtra(EXTRA_TITLE))
            .setMessage(intent.getStringExtra(EXTRA_MESSAGE))
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> close(null) }
        if (input != null) {
            val pad = (20 * resources.displayMetrics.density).toInt()
            builder.setView(LinearLayout(this).apply {
                setPadding(pad, 0, pad, 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
        }
        dialog = builder.show().also { d ->
            // validate before closing: an invalid amount keeps the dialog open
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (requested == null || input == null) {
                    close(0.0)
                    return@setOnClickListener
                }
                val entered = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val amount = ManualBolusRule.editedAmount(requested, entered, step)
                if (amount == null) input.error = getString(R.string.medlink_smb_pending_amount_invalid, step, requested)
                else close(amount)
            }
        }
        // close the question together with the timeout
        mainHandler.postDelayed({ if (!pending.containsKey(id) && !isFinishing) finish() }, TIMEOUT_MS + 500)
    }

    private fun close(amount: Double?) {
        answer(id, amount)
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        if (isFinishing) answer(id, null)
        super.onDestroy()
    }
}
