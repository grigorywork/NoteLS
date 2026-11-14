package com.notessync.app.security

import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.passwordRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A fresh DB credential and one foreground generation authorize a task, including a task in trash. */
internal class TaskPasswordController(private val scope: CoroutineScope, private val db: AppDatabase) {
    @Volatile private var generation = 0L
    private var job: Job? = null
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    fun unlock(id: Long, syncId: String, deleted: Boolean, password: String, onResult: (Boolean) -> Unit) {
        cancel()
        val token = generation
        mutableBusy.value = true
        job = scope.launch {
            var accepted = false
            try {
                val binding = db.noteDao().getTaskProtection(id)
                val record = binding?.passwordRecord()
                val verified = binding != null && binding.syncId == syncId && syncId.isNotBlank() &&
                    binding.isTask && !binding.isPurged && binding.isDeleted == deleted && record != null &&
                    withContext(Dispatchers.Default) { PasswordProtector.verify(password, record) }
                if (verified) accepted = db.withTransaction {
                    val unchanged = db.noteDao().getTaskProtection(id) == binding
                    withContext(Dispatchers.Main.immediate) {
                        val valid = unchanged && generation == token
                        if (valid) ProtectionSession.unlockTask(syncId)
                        valid
                    }
                }
            } catch (error: Exception) { if (error is CancellationException) throw error }
            finally { if (generation == token) { mutableBusy.value = false; onResult(accepted) } }
        }
    }

    fun cancel() {
        generation++
        job?.cancel(); job = null
        mutableBusy.value = false
    }
}
