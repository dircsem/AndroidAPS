package app.aaps.pump.medtronic.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import app.aaps.core.ui.R as CoreUiR
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.data.MedLinkResumeRule
import java.text.DateFormat
import java.util.Date

/**
 * B15: a guard for a dead app. On the Veo a suspend through MedLink has no end time: only the app starts the pump
 * again. While the app works, `MedLinkResumeRule` starts the pump and alarms, and every sign of life moves this guard
 * 20 min ahead, so it never sounds then. When the app is dead nothing runs: the Android alarm fires and Android starts
 * [MedLinkSuspendGuardReceiver] (in a new app process when needed).
 *
 * User, 2026-11-19 (no extra alarms at night): the alarm sound repeats until the user acts, it opens AAPS over the lock
 * screen, it comes at most once for each automatic STOP, and "Silence 30 min" asks for one more reminder.
 *
 * Plain Android only (no app objects): it must work when the app itself is broken. Note: it runs in the app process, so
 * it can not sound while the app is frozen (a separate process needs a change in `MainApp`, not done).
 */
object MedLinkSuspendGuard {

    /** v2: the AAPS alarm sound. Android keeps the sound of an existing channel, so the id had to change. */
    const val CHANNEL_ID = "medlink_suspend_guard_v2"
    /** The first channel, with the phone alarm sound. Removed in [ensureChannel]. */
    private const val OLD_CHANNEL_ID = "medlink_suspend_guard"
    const val NOTIFICATION_ID = 7_351_001
    private const val REQUEST_CODE = 7_351_001
    private const val SILENCE_REQUEST_CODE = 7_351_002
    const val EXTRA_STOPPED_AT = "stopped_at"
    const val EXTRA_SNOOZE = "snooze"
    const val ACTION_SILENCE = "app.aaps.pump.medtronic.MEDLINK_GUARD_SILENCE"
    const val SILENCE_MS = 30 * 60_000L

    /** Own small file: the receiver may run in a fresh process, before the app is ready. */
    private const val PREFS = "medlink_suspend_guard"
    private const val KEY_FIRED_FOR_STOP = "fired_for_stop"

    private fun pendingIntent(context: Context, stoppedAt: Long, snooze: Boolean, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, MedLinkSuspendGuardReceiver::class.java).putExtra(EXTRA_STOPPED_AT, stoppedAt).putExtra(EXTRA_SNOOZE, snooze),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Sets (or moves) the guard alarm to [at]. [stoppedAt] = time of the automatic STOP, shown in the message.
     * [snooze] = the reminder after "Silence 30 min" (it shows even when the alarm for this STOP was shown).
     */
    fun schedule(context: Context, at: Long, stoppedAt: Long, snooze: Boolean = false) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, stoppedAt, snooze, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // exact when the phone allows it, else inexact (a few minutes later in Doze): both work with the app dead
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms())
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Removes the guard alarm and its notification (the pump runs again, or the user stopped it). */
    fun cancel(context: Context) {
        pendingIntent(context, 0L, false, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Android 14+: the user must allow full screen notifications for AAPS, else the alarm can not open AAPS. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.medlink_suspend_guard_channel), NotificationManager.IMPORTANCE_HIGH)
        // the same sound as the other AAPS alarms (not the phone alarm sound)
        channel.setSound(
            Uri.parse("android.resource://${context.packageName}/${CoreUiR.raw.alarm}"),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    internal fun firedForStop(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_FIRED_FOR_STOP, 0L)

    internal fun markFired(context: Context, stoppedAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_FIRED_FOR_STOP, stoppedAt).apply()
    }

    internal fun silenceIntent(context: Context, stoppedAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, SILENCE_REQUEST_CODE,
            Intent(context, MedLinkSuspendGuardReceiver::class.java).setAction(ACTION_SILENCE).putExtra(EXTRA_STOPPED_AT, stoppedAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** B15: the guard alarm fired (the pump may still be suspended and the app did not start it), or "Silence 30 min". */
class MedLinkSuspendGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val stoppedAt = intent.getLongExtra(MedLinkSuspendGuard.EXTRA_STOPPED_AT, 0L)
        if (intent.action == MedLinkSuspendGuard.ACTION_SILENCE) {
            // the sound stops now; one more reminder in 30 min, unless the app starts the pump before (it cancels it)
            context.getSystemService(NotificationManager::class.java)?.cancel(MedLinkSuspendGuard.NOTIFICATION_ID)
            MedLinkSuspendGuard.schedule(context, System.currentTimeMillis() + MedLinkSuspendGuard.SILENCE_MS, stoppedAt, snooze = true)
            return
        }
        val snooze = intent.getBooleanExtra(MedLinkSuspendGuard.EXTRA_SNOOZE, false)
        if (!MedLinkResumeRule.guardShouldShow(stoppedAt, MedLinkSuspendGuard.firedForStop(context), snooze)) return
        MedLinkSuspendGuard.markFired(context, stoppedAt)

        MedLinkSuspendGuard.ensureChannel(context)
        val time = if (stoppedAt > 0L) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stoppedAt)) else "--"
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val text = context.getString(R.string.medlink_suspend_guard_message, time)
        val notification = NotificationCompat.Builder(context, MedLinkSuspendGuard.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.medlink_pump_still_suspended_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.medlink_silence_30_min), MedLinkSuspendGuard.silenceIntent(context, stoppedAt))
            .apply {
                open?.let {
                    setContentIntent(it)
                    // over the lock screen: opens AAPS, which then starts the pump (B12 rule)
                    setFullScreenIntent(it, true)
                }
            }
            .build()
        // the sound repeats until the user opens, silences or dismisses the alarm
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java)?.notify(MedLinkSuspendGuard.NOTIFICATION_ID, notification)
    }
}
