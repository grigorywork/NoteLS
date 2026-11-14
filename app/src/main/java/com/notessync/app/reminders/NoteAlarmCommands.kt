package com.notessync.app.reminders

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.i18n.uiText
import com.notessync.app.notes.text.notePreviewText

internal enum class AlarmCommandResult { APPLIED, STALE, UNAVAILABLE }

/** Storage/platform boundary. Accepted commands still require current DB identity and caller generation. */
internal interface NoteAlarmCommands {
    suspend fun schedule(id: Long, syncId: String, at: Long, timer: Boolean, current: () -> Boolean): AlarmCommandResult
    suspend fun cancel(id: Long, syncId: String, timer: Boolean, current: () -> Boolean)
}

internal class RoomNoteAlarmCommands(context: Context, private val db: AppDatabase = AppDatabase.get(context)) : NoteAlarmCommands {
    private val app = context.applicationContext
    override suspend fun schedule(id: Long, syncId: String, at: Long, timer: Boolean,
        current: () -> Boolean): AlarmCommandResult = db.withTransaction {
        val note = db.noteDao().getAlarmSnapshot(id) ?: return@withTransaction AlarmCommandResult.STALE
        val target = if (timer) note.taskCountdownEndAt else note.reminderAt
        if (!current() || note.syncId != syncId || note.isDeleted || note.isPurged ||
            (timer && !note.isTask) || (note.isTask && note.isCompleted) || target != at) return@withTransaction AlarmCommandResult.STALE
        val title = if (note.hidden) uiText("Защищённая запись") else notePreviewText(note.title, 256).ifBlank { uiText("Без названия") }
        if (ReminderScheduler.scheduleAccepted(app, id, title, at, syncId, timer)) AlarmCommandResult.APPLIED else AlarmCommandResult.UNAVAILABLE
    }
    override suspend fun cancel(id: Long, syncId: String, timer: Boolean, current: () -> Boolean) {
        db.withTransaction {
            val note = db.noteDao().getAlarmSnapshot(id) ?: return@withTransaction
            if (!current() || note.syncId != syncId) return@withTransaction
            val target = if (timer) note.taskCountdownEndAt else note.reminderAt
            if (target == null || note.isDeleted || note.isPurged || (note.isTask && note.isCompleted) || (timer && !note.isTask)) {
                if (timer) ReminderScheduler.cancelTimer(app, id) else ReminderScheduler.cancel(app, id)
            }
        }
    }
}
