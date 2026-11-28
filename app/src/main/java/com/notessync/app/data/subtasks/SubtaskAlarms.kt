package com.notessync.app.data.subtasks

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.i18n.uiText
import com.notessync.app.reminders.ReminderReceiver
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.tasks.running.RunningTaskEntry
import com.notessync.app.tasks.running.RunningTaskNotifications
import com.notessync.app.widget.WidgetNoteLinks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One nearest one-shot alarm for all children, never one alarm per row or a periodic poll. */
internal object SubtaskAlarms {
    private val lock = Mutex()
    internal const val ACTION = "com.notessync.app.SUBTASK_DEADLINE"
    private const val PREFIX = "notels:subtask-alarm:"
    internal val uri: Uri get() = Uri.parse("notels://subtask-alarm/nearest")
    private fun intent(context: Context) = Intent(context, SubtaskAlarmReceiver::class.java).setAction(ACTION).setData(uri)

    internal fun alertParentIds(context: Context): List<Long> = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }
        .mapNotNull { it.tag.removePrefix(PREFIX).substringBefore(':').toLongOrNull()?.takeIf { id -> id > 0 } }
        .distinct().sorted().take(20)

    suspend fun reconcile(context: Context, db: AppDatabase = AppDatabase.get(context)) = lock.withLock {
        db.withTransaction {
            cleanup(context, db)
            schedule(context, db.subtaskDao().nextAlarm())
        }
    }
    private fun schedule(context: Context, next: Long?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (next == null) {
            PendingIntent.getBroadcast(context, 0, intent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                manager.cancel(it); it.cancel()
            }
            return
        }
        val pending = PendingIntent.getBroadcast(context, 0, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        val trigger = maxOf(next, if (now == Long.MAX_VALUE) now else now + 1)
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending) }
                .onFailure { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending) }.getOrThrow()
        } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }
    private suspend fun cleanup(context: Context, db: AppDatabase) {
        val manager = context.getSystemService(NotificationManager::class.java)
        var changed = false
        for (posted in manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }) {
            val parts = posted.tag.removePrefix(PREFIX).split(':')
            val parent = parts.getOrNull(0)?.toLongOrNull()
            val row = if (parent != null && parts.size == 3) db.subtaskDao().getEntry(parent, parts[2]) else null
            // A delivered alert survives a stopwatch pause/text save; completion/removal/restart clears it.
            if (row == null || row.syncId != Uri.decode(parts.getOrNull(1).orEmpty()) ||
                row.subtaskFinishedAt != posted.notification.extras.getLong("notels_subtask_deadline", 0) ||
                (row.hidden && posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() != uiText("Защищённая запись"))) { manager.cancel(posted.tag, posted.id); changed = true }
        }
        if (changed) RunningTaskNotifications.alertsChanged()
    }
    suspend fun deliver(context: Context, db: AppDatabase = AppDatabase.get(context), now: Long = System.currentTimeMillis()) = lock.withLock {
        val rows = db.withTransaction {
            val due = db.subtaskDao().due(now)
            if (due.isNotEmpty()) com.notessync.app.reminders.AlarmReconciler.requireRecovery(context)
            for (row in due) {
                currentCoroutineContext().ensureActive()
                db.subtaskDao().updateTiming(row.id, requireNotNull(row.subtaskId), row.taskElapsedMillis,
                    row.taskTimerStartedAt, 0, null, false, row.taskCountdownEndAt)
                db.noteDao().markContentChanged(row.id, now)
            }
            due
        }
        if (rows.isNotEmpty()) com.notessync.app.data.backup.BackupManager.noteChanged()
        cleanup(context, db)
        if (ReminderScheduler.canNotify(context)) for (row in rows.takeLast(20)) db.withTransaction {
            currentCoroutineContext().ensureActive()
            val fresh = db.subtaskDao().getEntry(row.id, requireNotNull(row.subtaskId)) ?: return@withTransaction
            if (fresh.syncId == row.syncId && fresh.taskCountdownEndAt == null && fresh.taskCountdownMillis == 0L && fresh.subtaskFinishedAt == row.taskCountdownEndAt) {
                post(context, fresh)
            }
        }
        // A bounded backlog uses another one-shot delivery; no interval/ticker is created.
        schedule(context, db.subtaskDao().nextAlarm())
        runCatching { RunningTaskNotifications.refreshNow(context) }
    }
    @android.annotation.SuppressLint("MissingPermission") // Fresh permission check, revocation caught by receiver.
    private fun post(context: Context, row: RunningTaskEntry) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }
        existing.sortedBy { it.postTime }.take((existing.size - 19).coerceAtLeast(0)).forEach { manager.cancel(it.tag, it.id) }
        val open = if (row.hidden) Intent(context, MainActivity::class.java) else WidgetNoteLinks.intent(context, -91, row.id, row.syncId)
        val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val generic = NotificationCompat.Builder(context, ReminderReceiver.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(uiText("Таймер подзадачи завершён")).build()
        val notification = NotificationCompat.Builder(context, ReminderReceiver.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (row.hidden) uiText("Защищённая запись") else row.title.ifBlank { uiText("Подзадача") })
            .setContentText(uiText("Таймер подзадачи завершён")).addExtras(android.os.Bundle().apply { putLong("notels_subtask_deadline", requireNotNull(row.subtaskFinishedAt)) }).setContentIntent(content).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(generic).build()
        manager.notify("$PREFIX${row.id}:${Uri.encode(row.syncId)}:${row.subtaskId}", 0, notification)
        RunningTaskNotifications.alertsChanged()
    }
}
