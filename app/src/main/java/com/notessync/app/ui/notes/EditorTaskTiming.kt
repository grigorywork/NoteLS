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

/** Each alarm kind has its own generation. Every suspension must retain the chosen target and parent. */
internal class EditorTaskTiming(private val app: Application, private val scope: CoroutineScope,
    private val task: () -> Boolean, private val completed: () -> Boolean, private val removed: () -> Boolean,
    private val parentId: () -> Long, private val titleForNotification: suspend () -> String,
    private val persist: suspend () -> Boolean, private val report: (EditorMessage) -> Unit,
    private val parentSyncId: () -> String = { "" }) {
    private val isTask get() = task()
    private val isCompleted get() = completed()
    private val deleted get() = removed()
    private val currentNoteId get() = parentId()
    private val exists get() = currentNoteId > 0
    private var reminderGeneration = 0L
    private var countdownGeneration = 0L
    var taskElapsedMillis by mutableLongStateOf(0L)
    var taskTimerStartedAt by mutableStateOf<Long?>(null)
    var taskCountdownMillis by mutableLongStateOf(0L)
    var taskCountdownEndAt by mutableStateOf<Long?>(null)
    var reminderAt by mutableStateOf<Long?>(null)

    private fun save() { scope.launch { safely { persist() } } }
    private suspend fun safely(action: suspend () -> Unit) {
        try { action() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            report(EditorMessage.SAVE_FAILED)
        }
    }
    fun toggleTaskTimer() {
        if (deleted || !isTask || (isCompleted && taskTimerStartedAt == null)) return
        val timer = TaskTimer.toggle(taskElapsedMillis, taskTimerStartedAt, System.currentTimeMillis())
        taskElapsedMillis = timer.elapsedMillis; taskTimerStartedAt = timer.startedAt; save()
    }
    fun resetTaskTimer() {
        if (deleted || (taskElapsedMillis == 0L && taskTimerStartedAt == null)) return
        taskElapsedMillis = 0L; taskTimerStartedAt = null; save()
    }
    fun countdownRemainingAt(now: Long = System.currentTimeMillis()) =
        TaskTimer.countdownRemaining(taskCountdownMillis, taskCountdownEndAt, now)
    fun countdownRunning() = taskCountdownEndAt != null
    fun countdownFinishedNow(now: Long = System.currentTimeMillis()) =
        TaskTimer.countdownFinished(taskCountdownMillis, taskCountdownEndAt, now)
    fun startCountdownUntil(target: Long): Boolean {
        val now = System.currentTimeMillis()
        if (deleted || !isTask || isCompleted || target <= now) return false
        taskCountdownMillis = target - now; taskCountdownEndAt = target
        launchCountdown(target); return true
    }
    fun startCountdown(durationMillis: Long) {
        val now = System.currentTimeMillis()
        if (durationMillis <= 0 || durationMillis > Long.MAX_VALUE - now) return
        startCountdownUntil(now + durationMillis)
    }
    fun pauseCountdown() {
        val endAt = taskCountdownEndAt ?: return
        taskCountdownMillis = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
        taskCountdownEndAt = null; cancelCountdownAlarm()
    }
    fun resumeCountdown() {
        if (deleted || !isTask || isCompleted || taskCountdownEndAt != null || taskCountdownMillis <= 0) return
        val now = System.currentTimeMillis()
        if (taskCountdownMillis > Long.MAX_VALUE - now) return
        val target = now + taskCountdownMillis
        taskCountdownEndAt = target; launchCountdown(target)
    }
    fun cancelCountdown() {
        if (taskCountdownMillis == 0L && taskCountdownEndAt == null) return
        taskCountdownMillis = 0L; taskCountdownEndAt = null; cancelCountdownAlarm()
    }
    private fun countdownValid(token: Long, target: Long) = token == countdownGeneration &&
        !deleted && isTask && !isCompleted && taskCountdownEndAt == target
    private fun launchCountdown(target: Long) {
        val token = ++countdownGeneration
        scope.launch { safely {
            if (!persist() || !countdownValid(token, target)) return@safely
            val id = currentNoteId; val syncId = parentSyncId()
            if (id <= 0 || syncId.isBlank()) return@safely
            val title = titleForNotification()
            if (!countdownValid(token, target) || id != currentNoteId || syncId != parentSyncId()) return@safely
            if (!ReminderScheduler.scheduleTimer(app, id, title, target, syncId)) report(EditorMessage.REMINDER_SCHEDULE_FAILED)
        } }
    }
    fun cancelCountdownAlarm() {
        val token = ++countdownGeneration
        scope.launch { safely {
            if (persist() && token == countdownGeneration && taskCountdownEndAt == null && !deleted &&
                exists && parentSyncId().isNotBlank()) ReminderScheduler.cancelTimer(app, currentNoteId)
        } }
    }
    private fun reminderValid(token: Long, target: Long) = token == reminderGeneration &&
        !deleted && !(isTask && isCompleted) && reminderAt == target
    fun setReminder(at: Long) {
        if (deleted) return
        if (at <= System.currentTimeMillis()) { report(EditorMessage.REMINDER_IN_PAST); return }
        if (!ReminderScheduler.canNotify(app)) { report(EditorMessage.REMINDER_NOTIFICATIONS_DISABLED); return }
        reminderAt = at
        val token = ++reminderGeneration
        scope.launch { safely {
            if (!persist() || !reminderValid(token, at)) return@safely
            val id = currentNoteId; val syncId = parentSyncId()
            if (id <= 0 || syncId.isBlank()) return@safely
            val title = titleForNotification()
            if (!reminderValid(token, at) || id != currentNoteId || syncId != parentSyncId()) return@safely
            if (!ReminderScheduler.schedule(app, id, title, at, syncId) && reminderValid(token, at)) {
                val cleared = ++reminderGeneration
                reminderAt = null
                persist()
                if (reminderGeneration == cleared) report(EditorMessage.REMINDER_SCHEDULE_FAILED)
            }
        } }
    }
    fun reportReminderNotificationsDisabled() = report(EditorMessage.REMINDER_NOTIFICATIONS_DISABLED)
    fun clearReminder() {
        val token = ++reminderGeneration
        reminderAt = null
        scope.launch { safely {
            if (persist() && token == reminderGeneration && reminderAt == null && !deleted &&
                exists && parentSyncId().isNotBlank()) ReminderScheduler.cancel(app, currentNoteId)
        } }
    }
}
