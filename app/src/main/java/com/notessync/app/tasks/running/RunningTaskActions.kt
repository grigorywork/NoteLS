package com.notessync.app.tasks.running

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.reminders.AlarmReconciler
import com.notessync.app.widget.CalendarWidgetProvider

/** Atomic metadata-only command: a stale notification never writes an old document over new text. */
internal object RunningTaskActions {
    suspend fun apply(context: Context, control: RunningControl, now: Long = System.currentTimeMillis()): Boolean {
        val db = AppDatabase.get(context)
        val changed = db.withTransaction {
            val row = db.noteDao().getRunningTask(control.id) ?: return@withTransaction false
            if (!row.matches(control)) return@withTransaction false
            if (control.clock == RunningClock.COUNTDOWN && (row.taskCountdownEndAt ?: 0) <= now) return@withTransaction false
            val complete = control.action == RunningAction.COMPLETE
            val pauseWatch = complete || control.clock == RunningClock.STOPWATCH
            val pauseCountdown = complete || control.clock == RunningClock.COUNTDOWN
            if (pauseCountdown || complete) AlarmReconciler.requireRecovery(context)
            db.noteDao().updateRunningTask(row.id, row.syncId,
                if (pauseWatch) RunningClockTime.elapsed(row.taskElapsedMillis, row.taskTimerStartedAt, now) else row.taskElapsedMillis,
                if (pauseWatch) null else row.taskTimerStartedAt,
                if (pauseCountdown) row.taskCountdownEndAt?.let { RunningClockTime.remaining(it, now) } ?: row.taskCountdownMillis.coerceAtLeast(0) else row.taskCountdownMillis,
                if (pauseCountdown) null else row.taskCountdownEndAt, complete, if (complete) now else null, now) == 1
        }
        if (changed) {
            // Durable recovery was set before commit; foreground recovery can retry a failed alarm update.
            runCatching { AlarmReconciler.refreshNotes(context, db, setOf(control.id)) }
            CalendarWidgetProvider.refreshAll(context)
        }
        return changed
    }
}
