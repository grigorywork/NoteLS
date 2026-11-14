package com.notessync.app.ui.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.AlarmReconciler
import com.notessync.app.ui.tasks.toOrdinaryNote
import com.notessync.app.data.local.model.*
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface EditorProtectionParent {
    val editable: Boolean
    val task: Boolean
    val isProtected: Boolean
    val noteId: Long
    val syncId: String
    suspend fun flush(): Boolean
    fun denied()
    fun changed(record: PasswordRecord?, timestamp: Long)
    fun converted(note: NoteEntity)
    fun failed()
}

/** Mutate only protection columns after fresh authorization; cached editor credentials cannot grant access. */
internal class EditorTaskProtection(context: Context, private val db: AppDatabase,
    private val scope: CoroutineScope, private val saveMutex: Mutex, private val parent: EditorProtectionParent) {
    private val app = context.applicationContext
    private val actions = NoteActions(context, db)
    private var pending: Job? = null
    @Volatile private var generation = 0L
    var busy by mutableStateOf(false); private set
    fun cancel() { generation++; busy = false }
    fun protect(password: String) = change(password, remove = false) {}
    fun remove(password: String, onResult: (Boolean) -> Unit) = change(password, remove = true, onResult)

    suspend fun awaitIdle() {
        while (true) {
            val current = pending ?: return
            current.join()
            if (pending === current) return
        }
    }
    fun convertToNote() {
        if (busy || !parent.editable || !parent.task) return
        val token = ++generation
        busy = true
        pending = scope.launch {
            try {
                if (!parent.flush() || generation != token || !parent.editable) return@launch
                saveMutex.withLock {
                    if (generation != token || !parent.editable) return@withLock
                    withContext(NonCancellable) {
                        val converted = db.withTransaction {
                            val fresh = db.noteDao().getById(parent.noteId) ?: return@withTransaction null
                            if (fresh.syncId != parent.syncId || fresh.isDeleted || fresh.isPurged ||
                                !actions.canAccess(fresh)) { parent.denied(); return@withTransaction null }
                            val now = if (fresh.updatedAt == Long.MAX_VALUE) Long.MAX_VALUE
                                else maxOf(System.currentTimeMillis(), fresh.updatedAt + 1)
                            val note = fresh.toOrdinaryNote(now)
                            if (fresh.reminderAt != null || fresh.taskCountdownEndAt != null) AlarmReconciler.requireRecovery(app)
                            db.noteDao().update(note)
                            note
                        }
                        if (converted != null) {
                            parent.converted(converted)
                            ProtectionSession.lockTask(converted.syncId)
                            runCatching { AlarmReconciler.refreshNotes(app, db, setOf(converted.id)) }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                parent.failed()
            } finally { if (generation == token) busy = false }
        }
    }

    private fun change(password: String, remove: Boolean, onResult: (Boolean) -> Unit) {
        if (busy || !parent.editable || !parent.task || parent.isProtected != remove) { onResult(false); return }
        val token = ++generation
        busy = true
        pending = scope.launch {
            var applied = false
            try {
                if (!parent.flush() || generation != token || !parent.editable) return@launch
                val expected = db.noteDao().getTaskProtection(parent.noteId) ?: return@launch
                if (!expected.isTask || expected.isDeleted || expected.isPurged || expected.syncId.isBlank() ||
                    expected.isProtected != remove || expected.syncId != parent.syncId) return@launch
                val record = if (remove) {
                    val current = expected.passwordRecord() ?: return@launch
                    if (!withContext(Dispatchers.Default) { PasswordProtector.verify(password, current) }) return@launch
                    null
                } else withContext(Dispatchers.Default) { PasswordProtector.create(password) }
                if (generation != token || !parent.editable) return@launch
                saveMutex.withLock {
                    if (generation != token || !parent.editable) return@withLock
                    // Commit and matching editor cache must complete together even if the screen exits.
                    withContext(NonCancellable) {
                        val timestamp = db.withTransaction {
                            val fresh = db.noteDao().getById(expected.id) ?: return@withTransaction null
                            if (fresh.protectionSnapshot() != expected || parent.noteId != expected.id ||
                                generation != token) return@withTransaction null
                            if (!actions.canAccess(fresh)) { parent.denied(); return@withTransaction null }
                            val now = if (fresh.updatedAt == Long.MAX_VALUE) Long.MAX_VALUE
                                else maxOf(System.currentTimeMillis(), fresh.updatedAt + 1)
                            db.noteDao().updateTaskProtection(fresh.id, record != null, record?.hash, record?.salt, now)
                            now
                        }
                        if (timestamp != null) {
                            parent.changed(record, timestamp)
                            if (remove) ProtectionSession.lockTask(expected.syncId)
                            else db.withTransaction {
                                val fresh = db.noteDao().getTaskProtection(expected.id)
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == token && fresh == expected.copy(isProtected = true,
                                            passwordHash = record!!.hash, passwordSalt = record.salt)) {
                                        ProtectionSession.unlockTask(expected.syncId)
                                    }
                                }
                            }
                            applied = true
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                parent.failed()
            } finally { if (generation == token) { busy = false; onResult(applied) } }
        }
    }
}
