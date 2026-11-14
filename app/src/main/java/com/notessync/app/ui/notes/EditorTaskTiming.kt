package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notessync.app.ui.tasks.TaskTimer
import com.notessync.app.reminders.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Finite accepted commands outlive owner closure; generation and fresh storage guards prevent stale effects. */
internal class EditorTaskTiming(private val app: Application, private val scope: CoroutineScope,
    private val task: () -> Boolean, private val completed: () -> Boolean, private val removed: () -> Boolean,
    private val parentId: () -> Long, private val persist: suspend () -> Boolean, private val report: (EditorMessage) -> Unit,
    private val parentSyncId: () -> String = { "" }, private val acceptsNew: () -> Boolean = { true },
    private val alarms: NoteAlarmCommands = RoomNoteAlarmCommands(app)) {
    private val isTask get() = task()
    private val isCompleted get() = completed()
    private val deleted get() = removed()
    private val currentNoteId get() = parentId()
    private val exists get() = currentNoteId > 0
    private val canStart get() = scope.isActive && acceptsNew() && !deleted
    @Volatile private var reminderGeneration = 0L
    @Volatile private var countdownGeneration = 0L
    private val pending = ConcurrentHashMap.newKeySet<Job>()
    var taskElapsedMillis by mutableLongStateOf(0L)
    var taskTimerStartedAt by mutableStateOf<Long?>(null)
    var taskCountdownMillis by mutableLongStateOf(0L)
    var taskCountdownEndAt by mutableStateOf<Long?>(null)
    var reminderAt by mutableStateOf<Long?>(null)

    private fun command(action: suspend () -> Unit) {
        if (!scope.isActive) return
        val work = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try { action() }
            catch (error: Exception) { if (error is CancellationException) throw error; report(EditorMessage.SAVE_FAILED) }
        } }
        pending.add(work); work.invokeOnCompletion { pending.remove(work) }
    }
    suspend fun awaitIdle() { while (pending.isNotEmpty()) pending.toList().joinAll() }
    private fun save() = command { persist() }
    fun toggleTaskTimer() {
        if (!canStart || !isTask || (isCompleted && taskTimerStartedAt == null)) return
        val timer = TaskTimer.toggle(taskElapsedMillis, taskTimerStartedAt, System.currentTimeMillis())
        taskElapsedMillis = timer.elapsedMillis; taskTimerStartedAt = timer.startedAt; save()
    }
    fun resetTaskTimer() {
        if (!canStart || (taskElapsedMillis == 0L && taskTimerStartedAt == null)) return
        taskElapsedMillis = 0L; taskTimerStartedAt = null; save()
    }
    fun countdownRemainingAt(now: Long = System.currentTimeMillis()) = TaskTimer.countdownRemaining(taskCountdownMillis, taskCountdownEndAt, now)
    fun countdownRunning() = taskCountdownEndAt != null
    fun countdownFinishedNow(now: Long = System.currentTimeMillis()) = TaskTimer.countdownFinished(taskCountdownMillis, taskCountdownEndAt, now)
    fun startCountdownUntil(target: Long): Boolean {
        val now = System.currentTimeMillis()
        if (!canStart || !isTask || isCompleted || target <= now) return false
        taskCountdownMillis = target - now; taskCountdownEndAt = target; launchCountdown(target); return true
    }
    fun startCountdown(durationMillis: Long) {
        val now = System.currentTimeMillis()
        if (durationMillis <= 0 || durationMillis > Long.MAX_VALUE - now) return
        startCountdownUntil(now + durationMillis)
    }
    fun pauseCountdown() {
        if (!canStart) return
        val endAt = taskCountdownEndAt ?: return
        taskCountdownMillis = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
        taskCountdownEndAt = null; cancelCountdownAlarm()
    }
    fun resumeCountdown() {
        if (!canStart || !isTask || isCompleted || taskCountdownEndAt != null || taskCountdownMillis <= 0) return
        val now = System.currentTimeMillis()
        if (taskCountdownMillis > Long.MAX_VALUE - now) return
        val target = now + taskCountdownMillis; taskCountdownEndAt = target; launchCountdown(target)
    }
    fun cancelCountdown() {
        if (!canStart || (taskCountdownMillis == 0L && taskCountdownEndAt == null)) return
        taskCountdownMillis = 0L; taskCountdownEndAt = null; cancelCountdownAlarm()
    }
    private fun countdownValid(token: Long, target: Long) = token == countdownGeneration && !deleted && isTask && !isCompleted && taskCountdownEndAt == target
    private fun launchCountdown(target: Long) {
        val token = ++countdownGeneration
        command {
            if (!countdownValid(token, target) || !persist() || !countdownValid(token, target)) return@command
            val id = currentNoteId; val syncId = parentSyncId()
            if (id <= 0 || syncId.isBlank()) return@command
            val current = { countdownValid(token, target) && id == currentNoteId && syncId == parentSyncId() }
            if (alarms.schedule(id, syncId, target, true, current) == AlarmCommandResult.UNAVAILABLE && current()) report(EditorMessage.REMINDER_SCHEDULE_FAILED)
        }
    }
    fun cancelCountdownAlarm() {
        if (!canStart) return
        val token = ++countdownGeneration
        command {
            if (!persist() || token != countdownGeneration || taskCountdownEndAt != null || deleted || !exists) return@command
            val id = currentNoteId; val syncId = parentSyncId()
            alarms.cancel(id, syncId, true) { token == countdownGeneration && taskCountdownEndAt == null && !deleted && id == currentNoteId && syncId == parentSyncId() }
        }
    }
    private fun reminderValid(token: Long, target: Long) = token == reminderGeneration && !deleted && !(isTask && isCompleted) && reminderAt == target
    fun setReminder(at: Long) {
        if (!canStart) return
        if (at <= System.currentTimeMillis()) { report(EditorMessage.REMINDER_IN_PAST); return }
        if (!ReminderScheduler.canNotify(app)) { report(EditorMessage.REMINDER_NOTIFICATIONS_DISABLED); return }
        reminderAt = at
        val token = ++reminderGeneration
        command {
            if (!reminderValid(token, at) || !persist() || !reminderValid(token, at)) return@command
            val id = currentNoteId; val syncId = parentSyncId()
            if (id <= 0 || syncId.isBlank()) return@command
            val current = { reminderValid(token, at) && id == currentNoteId && syncId == parentSyncId() }
            if (alarms.schedule(id, syncId, at, false, current) == AlarmCommandResult.UNAVAILABLE && current()) {
                val cleared = ++reminderGeneration; reminderAt = null
                if (persist()) alarms.cancel(id, syncId, false) { cleared == reminderGeneration && reminderAt == null && id == currentNoteId && syncId == parentSyncId() }
                if (reminderGeneration == cleared) report(EditorMessage.REMINDER_SCHEDULE_FAILED)
            }
        }
    }
    fun reportReminderNotificationsDisabled() = report(EditorMessage.REMINDER_NOTIFICATIONS_DISABLED)
    fun clearReminder() {
        if (!canStart) return
        val token = ++reminderGeneration; reminderAt = null
        command {
            if (!persist() || token != reminderGeneration || reminderAt != null || deleted || !exists) return@command
            val id = currentNoteId; val syncId = parentSyncId()
            alarms.cancel(id, syncId, false) { token == reminderGeneration && reminderAt == null && !deleted && id == currentNoteId && syncId == parentSyncId() }
        }
    }
}
