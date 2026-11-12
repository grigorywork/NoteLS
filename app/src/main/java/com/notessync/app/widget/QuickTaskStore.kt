package com.notessync.app.widget

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.text.notePreviewText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QuickTaskDraft(val syncId: String, val text: String, val dayKey: Long, val reminderAt: Long?)
enum class QuickTaskWarning { PAST, NOTIFICATIONS, SCHEDULE }
data class QuickTaskSaved(val id: Long, val removed: Boolean = false, val warning: QuickTaskWarning? = null)

/** One shared write gate also covers overlapping old/new Activity instances with the same saved identity. */
class QuickTaskStore(context: Context, private val reminders: QuickTaskReminders = AndroidQuickTaskReminders(context.applicationContext)) {
    private val app = context.applicationContext
    private val db = AppDatabase.get(app)
    private val dao = db.noteDao()

    suspend fun save(draft: QuickTaskDraft): QuickTaskSaved = writes.withLock {
        val text = draft.text.trim()
        val now = System.currentTimeMillis()
        var previous: NoteEntity? = null
        var changed = false
        var warning: QuickTaskWarning? = null
        val note = db.withTransaction {
            previous = dao.getBySyncId(draft.syncId)
            val old = previous
            if (text.isEmpty()) {
                if (old != null && !old.isDeleted) { dao.softDelete(old.id); changed = true }
                return@withTransaction null
            }
            check(old?.isDeleted != true) { "This draft was deleted" }
            check(old?.isProtected != true && old?.notebookId?.let { db.notebookDao().getById(it)?.isProtected } != true) { "This draft is protected" }
            val requested = draft.reminderAt
            warning = when {
                requested == null -> null
                !reminders.canNotify() -> QuickTaskWarning.NOTIFICATIONS
                requested <= now && requested != old?.reminderAt -> QuickTaskWarning.PAST
                else -> null
            }
            val base = old ?: NoteEntity(syncId = draft.syncId)
            val candidate = base.copy(title = notePreviewText(text.lineSequence().first(), 60), content = text,
                dayKey = draft.dayKey, isTask = true, reminderAt = requested.takeIf { warning == null })
            if (old != null && candidate == old) return@withTransaction old
            changed = true
            val saved = candidate.copy(updatedAt = now, dirty = true)
            if (old == null) saved.copy(id = dao.upsert(saved)) else { dao.update(saved); saved }
        }
        if (note == null) {
            previous?.let { reminders.cancel(it.id) }
        } else if (note.isCompleted) {
            reminders.cancel(note.id)
        } else if (note.reminderAt == null) {
            if (previous?.reminderAt != null) reminders.cancel(note.id)
        } else if (note.reminderAt > now &&
            (previous?.reminderAt != note.reminderAt || previous?.title != note.title)) {
            if (!reminders.schedule(note.id, note.title, note.reminderAt)) {
                reminders.cancel(note.id)
                dao.clearFailedReminder(note.id, note.reminderAt)
                warning = QuickTaskWarning.SCHEDULE
                changed = true
            }
        }
        if (changed) {
            BackupManager.noteChanged()
            // A widget refresh failure must not make a committed task appear unsaved.
            runCatching { CalendarWidgetProvider.refreshAll(app) }
        }
        QuickTaskSaved(note?.id ?: 0L, removed = note == null && changed, warning = warning)
    }

    companion object { private val writes = Mutex() }
}
