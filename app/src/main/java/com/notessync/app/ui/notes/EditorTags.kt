package com.notessync.app.ui.notes

import androidx.room.withTransaction
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.data.local.model.nextChangeTimestamp
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal interface EditorTagsParent {
    val editable: Boolean
    val noteId: Long
    val syncId: String
    suspend fun flush(): Boolean
    fun changed(names: List<String>, beforeVersion: Long, version: Long)
    fun failed()
}

/** Owns finite accepted relation commands; a cached editor never owns a replacement record. */
internal class EditorTags(private val db: AppDatabase, private val scope: CoroutineScope,
    private val saveMutex: Mutex, private val parent: EditorTagsParent) {
    private data class Result(val names: List<String>, val beforeVersion: Long, val version: Long, val changed: Boolean)
    private val pending = ConcurrentHashMap.newKeySet<Job>()
    fun change(name: String, add: Boolean) {
        val clean = name.trim()
        if (!scope.isActive || !parent.editable || clean.isEmpty()) return
        val work = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                if (!parent.flush()) return@withContext
                saveMutex.withLock {
                    val result = mutate(parent.noteId, parent.syncId, clean, add) ?: return@withLock
                    parent.changed(result.names, result.beforeVersion, result.version)
                    if (result.changed) BackupManager.noteChanged()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                parent.failed()
            }
        } }
        pending.add(work); work.invokeOnCompletion { pending.remove(work) }
    }
    suspend fun awaitIdle() = pending.awaitPendingCommands()

    private suspend fun mutate(id: Long, syncId: String, name: String, add: Boolean): Result? = db.withTransaction {
        val fresh = db.noteDao().getTransformAccess(id) ?: return@withTransaction null
        if (fresh.syncId != syncId || !fresh.allowed(ProtectionSession.state.value)) return@withTransaction null
        val before = db.tagDao().getTagNamesForNote(id)
        if (before.any { it.equals(name, ignoreCase = true) } == add) return@withTransaction Result(before, fresh.updatedAt, fresh.updatedAt, false)
        val tag = db.tagDao().getByName(name)
        if (add) {
            val tagId = tag?.id ?: db.tagDao().insert(TagEntity(name = name)).takeIf { it > 0 }
                ?: db.tagDao().getByName(name)?.id ?: return@withTransaction null
            db.tagDao().attachToNote(NoteTagCrossRef(id, tagId))
        } else {
            if (tag == null) return@withTransaction Result(before, fresh.updatedAt, fresh.updatedAt, false)
            db.tagDao().detachFromNote(id, tag.id)
        }
        val timestamp = nextChangeTimestamp(fresh.updatedAt)
        db.noteDao().markContentChanged(id, timestamp)
        Result(db.tagDao().getTagNamesForNote(id), fresh.updatedAt, timestamp, true)
    }
}
