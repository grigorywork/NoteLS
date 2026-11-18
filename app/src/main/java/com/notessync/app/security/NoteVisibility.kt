package com.notessync.app.security

import com.notessync.app.data.local.model.NoteListEntry
import com.notessync.app.data.local.entity.NotebookEntity

/** Hide a cached row immediately when its session locks, before the debounced SQL query catches up. */
fun <T : NoteListEntry> visibleNotes(notes: List<T>, notebooks: List<NotebookEntity>, access: ProtectionState, trash: Boolean = false): List<T> {
    val lockedBooks = notebooks.asSequence().filter { it.isDiary || (it.isProtected && it.id !in access.notebookIds) }.map { it.id }.toSet()
    return notes.filter { note ->
        !note.isPurged && note.isDeleted == trash && note.notebookId !in lockedBooks && (!note.isProtected || (note.syncId.isNotBlank() && note.syncId in access.taskSyncIds))
    }
}
