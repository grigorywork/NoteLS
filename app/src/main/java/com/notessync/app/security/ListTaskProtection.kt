package com.notessync.app.security

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.*
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.widget.CalendarWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** List-menu credential changes touch no cached body and recheck identity and notebook access at commit. */
internal class ListTaskProtection(context: Context, private val db: AppDatabase, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val actions = NoteActions(app, db)
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    @Volatile private var generation = 0L
    private var pending: Job? = null

    fun cancel() { generation++; pending?.cancel(); pending = null; mutableBusy.value = false }

    fun change(target: NoteCardEntry, password: String, remove: Boolean, onResult: (Boolean) -> Unit) {
        if (mutableBusy.value) return
        val token = ++generation
        mutableBusy.value = true
        pending = scope.launch {
            var changed = false
            try {
                val expected = db.noteDao().getTaskProtection(target.id) ?: return@launch
                if (expected.syncId != target.syncId || expected.syncId.isBlank() || !expected.isTask ||
                    expected.isDeleted || expected.isPurged || expected.isProtected != remove) return@launch
                val record = if (remove) {
                    val old = expected.passwordRecord() ?: return@launch
                    if (!withContext(Dispatchers.Default) { PasswordProtector.verify(password, old) }) return@launch
                    null
                } else withContext(Dispatchers.Default) { PasswordProtector.create(password) }
                ensureActive()
                if (generation != token) return@launch
                withContext(NonCancellable) {
                    changed = db.withTransaction {
                        val fresh = db.noteDao().getById(expected.id) ?: return@withTransaction false
                        if (fresh.protectionSnapshot() != expected || generation != token || !actions.canAccess(fresh)) return@withTransaction false
                        db.noteDao().updateTaskProtection(fresh.id, record != null, record?.hash, record?.salt,
                            nextChangeTimestamp(fresh.updatedAt, System.currentTimeMillis()))
                        true
                    }
                    if (changed) {
                        if (remove) ProtectionSession.lockTask(expected.syncId)
                        else db.withTransaction {
                            val fresh = db.noteDao().getTaskProtection(expected.id)
                            withContext(Dispatchers.Main.immediate) {
                                if (generation == token && fresh == expected.copy(isProtected = true, passwordHash = record!!.hash,
                                        passwordSalt = record.salt)) ProtectionSession.unlockTask(expected.syncId)
                            }
                        }
                        BackupManager.noteChanged()
                        runCatching { BackupManager.requestAutoBackup(app, afterNote = true) }
                        runCatching { CalendarWidgetProvider.refreshAll(app) }
                    }
                }
            } catch (error: Exception) { if (error is CancellationException) throw error }
            finally { if (generation == token) { mutableBusy.value = false; onResult(changed) } }
        }
    }
}
