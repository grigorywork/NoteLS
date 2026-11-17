package com.notessync.app.data.local.model

/** Identity, visibility and clocks shared by complete documents and read-only card projections. */
interface NoteListEntry {
    val id: Long
    val syncId: String
    val notebookId: Long?
    val isDeleted: Boolean
    val isPurged: Boolean
    val isProtected: Boolean
    val isTask: Boolean
    val isCompleted: Boolean
    val taskDueAt: Long?
    val taskTimerStartedAt: Long?
    // Этап 80: нужен фильтру «Запущенные», чтобы кэш не показывал завершённый отсчёт.
    val taskCountdownEndAt: Long?
    val taskColor: String
    val noteColor: String
}

/** Cannot be passed to a document DAO write. Full bodies, tables and password records are absent. */
data class NoteCardEntry(
    override val id: Long = 0, override val syncId: String = "", val title: String = "", val content: String = "",
    val textFormat: String = "", val hasTables: Boolean = false, override val notebookId: Long? = null,
    val isFavorite: Boolean = false, override val isDeleted: Boolean = false, override val isPurged: Boolean = false,
    val isPinned: Boolean = false, override val noteColor: String = "", override val taskDueAt: Long? = null,
    override val isTask: Boolean = false, override val isCompleted: Boolean = false,
    override val taskColor: String = "violet", val taskPriority: Int = 0, val taskElapsedMillis: Long = 0,
    override val taskTimerStartedAt: Long? = null, val taskCountdownMillis: Long = 0, override val taskCountdownEndAt: Long? = null,
    override val isProtected: Boolean = false, val updatedAt: Long = 0
) : NoteListEntry

/** A closed card has no title, body, style, table document or password material. */
data class TaskUnlockTarget(val id: Long, val syncId: String, val notebookId: Long? = null, val isDeleted: Boolean = false)

internal fun NoteCardEntry.unlockTarget() = TaskUnlockTarget(id, syncId, notebookId, isDeleted)
