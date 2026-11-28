package com.notessync.app.reminders

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.NoteAlarmSnapshot
import com.notessync.app.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Finite200-row pages. A newer durable recovery revision cannot be cleared by an older scan. */
internal object AlarmReconciler {
    private val lock = Mutex()
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requested = AtomicBoolean(false)
    private fun prefs(context: Context) = context.getSharedPreferences("alarm_recovery", Context.MODE_PRIVATE)
    private data class Recovery(val pending: Boolean, val cursor: Long, val revision: Long)

    /** May be called inside a Room transaction: never acquire the alarm work mutex here. */
    suspend fun requireRecovery(context: Context) = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            val p = prefs(context)
            val old = p.getLong("revision", 0)
            val revision = if (old == Long.MAX_VALUE) 0 else old + 1
            check(p.edit().putBoolean("pending", true).putLong("cursor", 0).putLong("revision", revision).commit())
        }
    }
    fun resumePending(context: Context) {
        if (!requested.compareAndSet(false, true)) return
        scope.launch {
            try { withTimeout(8_000) { reschedule(context.applicationContext, reset = false) } }
            catch (_: Exception) { /* A later real foreground session resumes pending recovery. */ }
            finally { requested.set(false) }
        }
    }
    suspend fun reschedule(context: Context, reset: Boolean = true) = lock.withLock {
        if (reset) requireRecovery(context)
        val p = prefs(context)
        val state = synchronized(stateLock) {
            Recovery(p.getBoolean("pending", false), p.getLong("cursor", 0), p.getLong("revision", 0))
        }
        if (!state.pending) return@withLock
        val db = AppDatabase.get(context)
        var afterId = state.cursor
        while (true) {
            currentCoroutineContext().ensureActive()
            val last = db.withTransaction {
                val page = db.noteDao().getAlarmPage(afterId, System.currentTimeMillis())
                for (note in page) { currentCoroutineContext().ensureActive(); publish(context, note) }
                page.lastOrNull()?.id
            } ?: break
            afterId = last
            val unchanged = synchronized(stateLock) {
                if (p.getLong("revision", 0) != state.revision) false
                else { check(p.edit().putLong("cursor", afterId).commit()); true }
            }
            if (!unchanged) return@withLock // The new revision keeps its own pending cursor0.
        }
        com.notessync.app.data.subtasks.SubtaskAlarms.reconcile(context, db)
        synchronized(stateLock) {
            if (p.getLong("revision", 0) == state.revision) {
                check(p.edit().putBoolean("pending", false).putLong("cursor", 0).commit())
            }
        }
    }

    /** Call after commit, including partial/cancelled exchanges; only these IDs are touched. */
    suspend fun refreshNotes(context: Context, db: AppDatabase, ids: Set<Long>) = lock.withLock {
        for (batch in ids.toList().chunked(200)) {
            currentCoroutineContext().ensureActive()
            db.withTransaction {
                val current = db.noteDao().getAlarmSnapshots(batch).associateBy { it.id }
                for (id in batch) {
                    ReminderScheduler.cancel(context, id); ReminderScheduler.cancelTimer(context, id)
                    current[id]?.let { note ->
                        if (!note.isDeleted && !note.isPurged && !(note.isTask && note.isCompleted)) publish(context, note)
                    }
                }
            }
        }
        com.notessync.app.data.subtasks.SubtaskAlarms.reconcile(context, db)
    }
    private fun publish(context: Context, note: NoteAlarmSnapshot) {
        val title = if (note.hidden) uiText("Защищённая запись") else note.title.ifBlank { uiText("Без названия") }
        note.reminderAt?.let { ReminderScheduler.schedule(context, note.id, title, it, note.syncId) }
        if (note.isTask) note.taskCountdownEndAt?.let { ReminderScheduler.scheduleTimer(context, note.id, title, it, note.syncId) }
    }
}
