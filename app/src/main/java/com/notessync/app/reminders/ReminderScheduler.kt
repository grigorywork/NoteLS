package com.notessync.app.reminders

import com.notessync.app.i18n.uiText

import android.Manifest
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Постановка и отмена локальных напоминаний (Этап 3). */
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

    fun schedule(context: Context, noteId: Long, noteTitle: String, at: Long): Boolean {
        if (at <= System.currentTimeMillis()) return false
        return runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = pendingIntent(context, noteId, noteTitle)
            val exactAllowed = android.os.Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
            if (exactAllowed) {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                }.onFailure {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                }.getOrThrow()
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }.isSuccess
    }

    fun cancel(context: Context, noteId: Long) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, noteId, ""))
    }

    // ---------- Таймер задачи (Этап 30): отдельный request code и подпись ----------

    fun scheduleTimer(context: Context, noteId: Long, noteTitle: String, at: Long): Boolean {
        if (at <= System.currentTimeMillis()) return false
        return runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = timerPendingIntent(context, noteId, noteTitle)
            val exactAllowed = android.os.Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
            if (exactAllowed) {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                }.onFailure {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                }.getOrThrow()
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }.isSuccess
    }

    fun cancelTimer(context: Context, noteId: Long) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(timerPendingIntent(context, noteId, ""))
    }

    private fun timerPendingIntent(context: Context, noteId: Long, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
            .putExtra(ReminderReceiver.EXTRA_TITLE, title)
            .putExtra(ReminderReceiver.EXTRA_IS_TIMER, true)
        return PendingIntent.getBroadcast(
            context,
            (noteId xor (noteId ushr 32)).toInt() xor TIMER_REQUEST_SALT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val TIMER_REQUEST_SALT = 0x51A7E3

    private fun pendingIntent(context: Context, noteId: Long, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
            .putExtra(ReminderReceiver.EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context,
            (noteId xor (noteId ushr 32)).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
