package com.notessync.app.reminders

import com.notessync.app.i18n.uiText
import android.Manifest
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Full local identity plus kind names each alarm; the expected global identity/deadline validates delivery. */
object ReminderScheduler {
    fun canNotify(context: Context): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ReminderReceiver.CHANNEL_ID)
        return runtimePermissionGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE
    }


    fun schedule(context: Context, noteId: Long, noteTitle: String, at: Long, syncId: String): Boolean =
        scheduleAlarm(context, noteId, noteTitle, at, syncId, false)
    fun scheduleTimer(context: Context, noteId: Long, noteTitle: String, at: Long, syncId: String): Boolean =
        scheduleAlarm(context, noteId, noteTitle, at, syncId, true)

    internal fun alarmUri(id: Long, timer: Boolean): Uri = Uri.Builder().scheme("notels").authority("alarm")
        .appendPath(if (timer) "timer" else "reminder").appendPath(id.toString()).build()
    internal fun notificationTag(id: Long, timer: Boolean) = "notels:${if (timer) "timer" else "reminder"}:$id"
    private fun baseIntent(context: Context, id: Long, timer: Boolean) = Intent(context, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION_ALARM).setData(alarmUri(id, timer))
    internal fun deliveryIntent(context: Context, id: Long, timer: Boolean, at: Long, syncId: String, title: String = "") =
        baseIntent(context, id, timer).putExtra(ReminderReceiver.EXTRA_NOTE_ID, id)
            .putExtra(ReminderReceiver.EXTRA_IS_TIMER, timer).putExtra(ReminderReceiver.EXTRA_EXPECTED_AT, at)
            .putExtra(ReminderReceiver.EXTRA_SYNC_ID, syncId).putExtra(ReminderReceiver.EXTRA_TITLE, title.take(256))

    internal fun scheduleAccepted(context: Context, id: Long, title: String, at: Long, syncId: String, timer: Boolean) =
        scheduleAlarm(context, id, title, at, syncId, timer, accepted = true)

    private fun scheduleAlarm(context: Context, id: Long, title: String, at: Long, syncId: String, timer: Boolean, accepted: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val trigger = if (accepted) maxOf(at, now + 1) else at
        if (id <= 0 || syncId.isBlank() || at <= 0 || trigger <= now) return false
        return runCatching {
            val manager = context.getSystemService(AlarmManager::class.java)
            cancelLegacy(context, id, timer)
            val pending = PendingIntent.getBroadcast(context, 0, deliveryIntent(context, id, timer, at, syncId, title),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending) }
                    .onFailure { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending) }.getOrThrow()
            } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }.isSuccess
    }

    fun cancel(context: Context, noteId: Long) = cancelAlarm(context, noteId, false)
    fun cancelTimer(context: Context, noteId: Long) = cancelAlarm(context, noteId, true)
    private fun cancelAlarm(context: Context, id: Long, timer: Boolean) {
        PendingIntent.getBroadcast(context, 0, baseIntent(context, id, timer),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel()
        }
        cancelLegacy(context, id, timer)
        NotificationManagerCompat.from(context).cancel(notificationTag(id, timer), 0)
    }
    private fun cancelLegacy(context: Context, id: Long, timer: Boolean) {
        val folded = (id xor (id ushr 32)).toInt() xor (if (timer) 0x51A7E3 else 0)
        PendingIntent.getBroadcast(context, folded, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel()
        }
    }
}

/** Канал уведомлений создаётся при старте приложения. */
fun Context.createReminderChannel() {
    val manager = getSystemService(android.app.NotificationManager::class.java)
    manager.createNotificationChannel(
        android.app.NotificationChannel(
            ReminderReceiver.CHANNEL_ID,
            uiText("Напоминания"),
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
    )
}
