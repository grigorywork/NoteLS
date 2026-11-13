package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notessync.app.ui.tasks.TaskTimer
import com.notessync.app.reminders.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/** Stopwatch, countdown and reminder actions share the editor's save and lifetime boundaries. */
internal class EditorTaskTiming(private val app: Application, private val scope: CoroutineScope,
    private val task: () -> Boolean, private val completed: () -> Boolean, private val removed: () -> Boolean,
    private val parentId: () -> Long, private val titleForNotification: suspend () -> String,
    private val persist: suspend () -> Unit, private val report: (EditorMessage) -> Unit) {
    private val isTask get() = task()
    private val isCompleted get() = completed()
    private val deleted get() = removed()
    private val currentNoteId get() = parentId()
    private val exists get() = currentNoteId != -1L
    var taskElapsedMillis by mutableLongStateOf(0L)
    var taskTimerStartedAt by mutableStateOf<Long?>(null)
    var taskCountdownMillis by mutableLongStateOf(0L)
    var taskCountdownEndAt by mutableStateOf<Long?>(null)
    var reminderAt by mutableStateOf<Long?>(null)
    fun toggleTaskTimer() {
        if (!isTask || (isCompleted && taskTimerStartedAt == null)) return
        val timer = TaskTimer.toggle(
            taskElapsedMillis,
            taskTimerStartedAt,
            System.currentTimeMillis()
        )
        taskElapsedMillis = timer.elapsedMillis
        taskTimerStartedAt = timer.startedAt
        scope.launch { persist() }
    }

    fun resetTaskTimer() {
        if (taskElapsedMillis == 0L && taskTimerStartedAt == null) return
        taskElapsedMillis = 0L
        taskTimerStartedAt = null
        scope.launch { persist() }
    }

    // ---------- Обратный отсчёт (Этап 30) ----------

    /** Остаток в миллисекундах на момент [now]. */
    fun countdownRemainingAt(now: Long = System.currentTimeMillis()): Long =
        TaskTimer.countdownRemaining(taskCountdownMillis, taskCountdownEndAt, now)

    fun countdownRunning(): Boolean = taskCountdownEndAt != null

    fun countdownFinishedNow(now: Long = System.currentTimeMillis()): Boolean =
        TaskTimer.countdownFinished(taskCountdownMillis, taskCountdownEndAt, now)

    /** Установить длительность и сразу запустить отсчёт. */
    fun startCountdownUntil(target: Long): Boolean {
        val now = System.currentTimeMillis()
        if (!isTask || target <= now) return false
        taskCountdownMillis = target - now
        taskCountdownEndAt = target
        scope.launch {
            try { persist(); if (!deleted) scheduleCountdownAlarm() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                report(EditorMessage.SAVE_FAILED)
            }
        }
        return true
    }

    fun startCountdown(durationMillis: Long) {
        if (!isTask || durationMillis <= 0L) return
        val now = System.currentTimeMillis()
        taskCountdownMillis = durationMillis
        taskCountdownEndAt = TaskTimer.countdownStart(durationMillis, now)
        scope.launch {
            persist()
            scheduleCountdownAlarm()
        }
    }

    /** Пауза: остаток фиксируется, будильник снимается. */
    fun pauseCountdown() {
        val endAt = taskCountdownEndAt ?: return
        val now = System.currentTimeMillis()
        taskCountdownMillis = (endAt - now).coerceAtLeast(0L)
        taskCountdownEndAt = null
        cancelCountdownAlarm()
        scope.launch { persist() }
    }

    /** Продолжить с оставшегося времени. */
    fun resumeCountdown() {
        if (!isTask || taskCountdownEndAt != null || taskCountdownMillis <= 0L) return
        taskCountdownEndAt = TaskTimer.countdownStart(taskCountdownMillis, System.currentTimeMillis())
        scope.launch {
            persist()
            scheduleCountdownAlarm()
        }
    }

    /** Снять таймер полностью. */
    fun cancelCountdown() {
        if (taskCountdownMillis == 0L && taskCountdownEndAt == null) return
        taskCountdownMillis = 0L
        taskCountdownEndAt = null
        cancelCountdownAlarm()
        scope.launch { persist() }
    }

    private suspend fun scheduleCountdownAlarm() {
        val endAt = taskCountdownEndAt ?: return
        if (!exists) return
        val notificationTitle = titleForNotification()
        val scheduled = ReminderScheduler.scheduleTimer(app, currentNoteId, notificationTitle, endAt)
        if (!scheduled) {
            // Без будильника таймер продолжает жить только в интерфейсе.
            report(EditorMessage.REMINDER_SCHEDULE_FAILED)
        }
    }

    fun cancelCountdownAlarm() {
        if (exists) ReminderScheduler.cancelTimer(app, currentNoteId)
    }

    fun setReminder(at: Long) {
        if (at <= System.currentTimeMillis()) {
            report(EditorMessage.REMINDER_IN_PAST)
            return
        }
        if (!ReminderScheduler.canNotify(app)) {
            report(EditorMessage.REMINDER_NOTIFICATIONS_DISABLED)
            return
        }
        reminderAt = at
        scope.launch {
            persist()
            val notificationTitle = titleForNotification()
            val scheduled = ReminderScheduler.schedule(app, currentNoteId, notificationTitle, at)
            if (!scheduled) {
                reminderAt = null
                persist()
                report(EditorMessage.REMINDER_SCHEDULE_FAILED)
            }
        }
    }

    fun reportReminderNotificationsDisabled() {
        report(EditorMessage.REMINDER_NOTIFICATIONS_DISABLED)
    }

    fun clearReminder() {
        reminderAt = null
        if (exists) ReminderScheduler.cancel(app, currentNoteId)
        scope.launch { persist() }
    }

}
