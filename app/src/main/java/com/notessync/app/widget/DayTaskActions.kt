package com.notessync.app.widget

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.ui.tasks.TaskCompletion

/** A checkbox is an explicit request to complete a day entry as a task. */
internal object DayTaskActions {
    suspend fun setCompleted(
        context: Context,
        noteId: Long,
        dayKey: Long,
        completed: Boolean,
        now: Long = System.currentTimeMillis(),
        expectedSyncId: String? = null
    ): Boolean {
        val app = context.applicationContext
        val db = AppDatabase.get(app)
        val changed = db.withTransaction {
            val current = db.noteDao().getById(noteId) ?: return@withTransaction false
            // Recheck the current record: it may have changed since the list was drawn.
            if (current.isDeleted || current.isPurged || (expectedSyncId != null && current.syncId != expectedSyncId) || current.dayKey != dayKey || current.isProtected) {
                return@withTransaction false
            }
            if (current.notebookId?.let { db.notebookDao().getById(it)?.isProtected } == true) {
                return@withTransaction false
            }
            if (current.isTask && current.isCompleted == completed) return@withTransaction false
            if (!current.isTask && !completed) return@withTransaction false
            db.noteDao().update(TaskCompletion.toggle(current.copy(isTask = true), now))
            true
        }
        if (changed) {
            BackupManager.noteChanged()
            if (completed) {
                runCatching { ReminderScheduler.cancel(app, noteId) }
                runCatching { ReminderScheduler.cancelTimer(app, noteId) }
            }
            // Refresh failures must not turn a committed checkbox into an apparent save failure.
            runCatching { CalendarWidgetProvider.refreshAll(app) }
        }
        return changed
    }
}
