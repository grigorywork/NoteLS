package com.notessync.app.reminders

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.i18n.uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Finite200-row pages, no bodies or N+1 notebook reads. Interrupted recovery resumes on a real foreground event. */
internal object AlarmReconciler {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requested = AtomicBoolean(false)
    fun resumePending(context: Context) {
        if (!requested.compareAndSet(false, true)) return
        scope.launch {
            try { withTimeout(8_000) { reschedule(context.applicationContext, reset = false) } }
            catch (_: Exception) { /* Pending cursor remains for a later foreground session. */ }
            finally { requested.set(false) }
        }
    }
    suspend fun reschedule(context: Context, reset: Boolean = true) = lock.withLock {
        val prefs = context.getSharedPreferences("alarm_recovery", Context.MODE_PRIVATE)
        if (reset) check(prefs.edit().putBoolean("pending", true).putLong("cursor", 0).commit())
        if (!prefs.getBoolean("pending", false)) return@withLock
        val db = AppDatabase.get(context)
        var afterId = prefs.getLong("cursor", 0)
        while (true) {
            currentCoroutineContext().ensureActive()
            val last = db.withTransaction {
                val page = db.noteDao().getAlarmPage(afterId, System.currentTimeMillis())
                for (note in page) {
                    currentCoroutineContext().ensureActive()
                    val title = if (note.hidden) uiText("Защищённая запись") else note.title.ifBlank { uiText("Без названия") }
                    note.reminderAt?.let { ReminderScheduler.schedule(context, note.id, title, it, note.syncId) }
                    if (note.isTask) note.taskCountdownEndAt?.let { ReminderScheduler.scheduleTimer(context, note.id, title, it, note.syncId) }
                }
                page.lastOrNull()?.id
            } ?: break
            afterId = last
            check(prefs.edit().putLong("cursor", afterId).commit())
        }
        check(prefs.edit().clear().commit())
    }
}
