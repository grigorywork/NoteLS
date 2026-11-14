package com.notessync.app.reminders

import com.notessync.app.i18n.uiText
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.notes.text.notePreviewText
import kotlinx.coroutines.*

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val pending = goAsync() ?: return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) { deliver(app, intent) } }
            catch (_: Exception) { /* Stale, unavailable or timed-out delivery grants no notification. */ }
            finally { pending.finish() }
        }
    }

    @SuppressLint("MissingPermission") // Fresh canNotify check; onReceive still catches permission revocation races.
    private suspend fun deliver(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_NOTE_ID, -1)
        val timer = intent.getBooleanExtra(EXTRA_IS_TIMER, false)
        val syncId = intent.getStringExtra(EXTRA_SYNC_ID)?.takeIf { it.isNotBlank() } ?: return
        val expected = intent.getLongExtra(EXTRA_EXPECTED_AT, -1)
        if (id <= 0 || expected <= 0 || intent.data != ReminderScheduler.alarmUri(id, timer)) return
        val db = AppDatabase.get(context)
        db.withTransaction {
            val note = db.noteDao().getAlarmSnapshot(id) ?: return@withTransaction
            val at = if (timer) note.taskCountdownEndAt else note.reminderAt
            if (note.syncId != syncId || note.isDeleted || note.isPurged || (timer && !note.isTask) ||
                (note.isTask && note.isCompleted) || at != expected || at > System.currentTimeMillis()) return@withTransaction
            if (!ReminderScheduler.canNotify(context)) return@withTransaction
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = if (note.hidden) uiText("Защищённая запись")
                else notePreviewText(note.title, 256).ifBlank { uiText("Заметка") }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (timer) uiText("Время вышло") else uiText("Напоминание"))
                .setContentText(title).setContentIntent(open).setAutoCancel(true).build()
            NotificationManagerCompat.from(context).notify(ReminderScheduler.notificationTag(id, timer), 0, notification)
        }
    }
    companion object {
        const val CHANNEL_ID = "reminders"
        const val ACTION_ALARM = "com.notessync.app.ALARM"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TITLE = "note_title"
        const val EXTRA_IS_TIMER = "is_timer"
        const val EXTRA_SYNC_ID = "note_sync_id"
        const val EXTRA_EXPECTED_AT = "expected_at"
    }
}
