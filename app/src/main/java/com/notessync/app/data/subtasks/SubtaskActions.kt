package com.notessync.app.data.subtasks

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.tasks.running.*
import com.notessync.app.reminders.AlarmReconciler

/** Commands are scoped to fresh parent+child identities and the exact clock generation. */
internal object SubtaskActions {
    suspend fun apply(context: Context, command: RunningControl, now: Long, db: AppDatabase = AppDatabase.get(context)): Boolean {
        val child = command.subtaskId ?: return false
        val changed = db.withTransaction {
            val row = db.subtaskDao().getEntry(command.id, child) ?: return@withTransaction false
            if (!row.matches(command) || (command.clock == RunningClock.COUNTDOWN && (row.taskCountdownEndAt ?: 0) <= now)) return@withTransaction false
            val complete = command.action == RunningAction.COMPLETE
            val watch = complete || command.clock == RunningClock.STOPWATCH
            val timer = complete || command.clock == RunningClock.COUNTDOWN
            if (timer) AlarmReconciler.requireRecovery(context)
            val written = db.subtaskDao().updateTiming(row.id, child,
                if (watch) RunningClockTime.elapsed(row.taskElapsedMillis, row.taskTimerStartedAt, now) else row.taskElapsedMillis,
                if (watch) null else row.taskTimerStartedAt,
                if (timer) row.taskCountdownEndAt?.let { RunningClockTime.remaining(it, now) } ?: row.taskCountdownMillis else row.taskCountdownMillis,
                if (timer) null else row.taskCountdownEndAt, complete, row.subtaskFinishedAt) == 1
            if (written) db.noteDao().markContentChanged(row.id, now)
            written
        }
        if (changed) {
            if (command.clock == RunningClock.COUNTDOWN || command.action == RunningAction.COMPLETE) runCatching { SubtaskAlarms.reconcile(context, db) }
            com.notessync.app.data.backup.BackupManager.noteChanged()
        }
        return changed
    }
}
