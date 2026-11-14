package com.notessync.app.notes.actions

import com.notessync.app.data.local.model.nextChangeTimestamp

import com.notessync.app.i18n.uiText

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.security.ProtectionSession
import com.notessync.app.ui.tasks.TaskTimer
import com.notessync.app.widget.CalendarWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Fresh rows and one transaction: a batch never overwrites editor text or revives a tombstone. */
class NoteActions(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {
    private val app = context.applicationContext
    enum class Action { TRASH, RESTORE, PURGE, PIN, UNPIN }

    suspend fun apply(ids: Set<Long>?, action: Action, identities: Map<Long, String>? = null): Int = withContext(NonCancellable) {
        val discardedFiles = mutableListOf<String>()
        val changed = db.withTransaction {
            val changedRows = mutableListOf<Long>()
            val books = mutableMapOf<Long, com.notessync.app.data.local.entity.NotebookEntity?>()
            val selectedIds = ids?.toList()
            var offset = 0
            var afterId = 0L
            while (true) {
                val rows = if (selectedIds == null) {
                    db.noteDao().getTrashBatch(afterId).also { if (it.isNotEmpty()) afterId = it.last().id }
                } else {
                    if (offset >= selectedIds.size) break
                    val end = minOf(offset + 400, selectedIds.size)
                    db.noteDao().getByIds(selectedIds.subList(offset, end)).also { offset = end }
                }
                if (selectedIds == null && rows.isEmpty()) break
                for (note in rows) {
                    if (identities != null && identities[note.id] != note.syncId) continue
                    val bookId = note.notebookId
                    val book = if (bookId == null) null else {
                        if (bookId !in books) books[bookId] = db.notebookDao().getById(bookId)
                        books[bookId]
                    }
                    if (note.isPurged || !ProtectionSession.isUnlocked(note) || (book != null && !ProtectionSession.isUnlocked(book))) continue
                    val now = System.currentTimeMillis()
                    val version = nextChangeTimestamp(note.updatedAt, now)
                    val updated = when (action) {
                        Action.TRASH -> if (!note.isDeleted) paused(note, now).copy(isDeleted = true) else null
                        Action.RESTORE -> if (note.isDeleted) paused(note, now).copy(isDeleted = false) else null
                        Action.PURGE -> if (note.isDeleted) tombstone(note, version) else null
                        Action.PIN -> if (!note.isDeleted && !note.isPinned) note.copy(isPinned = true) else null
                        Action.UNPIN -> if (!note.isDeleted && note.isPinned) note.copy(isPinned = false) else null
                    } ?: continue
                    if (action == Action.PURGE) {
                        discardedFiles.addAll(db.attachmentDao().getByNoteId(note.id).map { it.filePath })
                        db.attachmentDao().deleteByNoteId(note.id)
                        db.tagDao().detachAllFromNote(note.id)
                    }
                    if (action in setOf(Action.TRASH, Action.RESTORE, Action.PURGE) &&
                        (note.reminderAt != null || note.taskCountdownEndAt != null)) com.notessync.app.reminders.AlarmReconciler.requireRecovery(app)
                    db.noteDao().update(updated.copy(updatedAt = version, dirty = true))
                    // Retain no bodies, tables or formatting while processing the next batch.
                    changedRows.add(updated.id)
                }
            }
            changedRows
        }
        // Commit first: a failed SQL write must never remove the original file.
        runCatching { db.withTransaction { deleteUnreferencedFiles(app, db, discardedFiles) } }
        if (action in setOf(Action.TRASH, Action.RESTORE, Action.PURGE) && changed.isNotEmpty()) {
            runCatching { com.notessync.app.reminders.AlarmReconciler.refreshNotes(app, db, changed.toSet()) }
        }
        if (changed.isNotEmpty()) runCatching { CalendarWidgetProvider.refreshAll(app) }
        changed.size
    }

    suspend fun canAccess(note: NoteEntity): Boolean = ProtectionSession.isUnlocked(note) &&
        (note.notebookId?.let { db.notebookDao().getById(it) }?.let { ProtectionSession.isUnlocked(it) } != false)

    companion object {
        fun paused(note: NoteEntity, now: Long): NoteEntity = note.copy(
            taskElapsedMillis = TaskTimer.elapsedAt(note.taskElapsedMillis, note.taskTimerStartedAt, now),
            taskTimerStartedAt = null,
            taskCountdownMillis = TaskTimer.countdownRemaining(note.taskCountdownMillis, note.taskCountdownEndAt, now),
            taskCountdownEndAt = null
        )

        /** Only sync identity and deletion metadata survive permanent deletion. */
        fun tombstone(note: NoteEntity, timestamp: Long): NoteEntity = NoteEntity(
            id = note.id, syncId = note.syncId, isDeleted = true, isPurged = true,
            createdAt = note.createdAt, updatedAt = timestamp, dirty = true
        )

        // Call while holding the DB write transaction; attachment writes use the same gate.
        suspend fun deleteUnreferencedFiles(context: Context, db: AppDatabase, paths: List<String>) = withContext(Dispatchers.IO) {
            val root = File(context.filesDir, "attachments").canonicalFile
            if (paths.isEmpty()) return@withContext
            val referenced = db.attachmentDao().getFilePaths().map { File(it).canonicalFile }.toHashSet()
            paths.distinct().forEach { path ->
                val file = File(path).canonicalFile
                if (file.toPath().startsWith(root.toPath()) && file != root && file !in referenced) file.delete()
            }
        }
    }
}
