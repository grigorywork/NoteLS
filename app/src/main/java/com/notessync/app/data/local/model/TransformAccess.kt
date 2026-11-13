package com.notessync.app.data.local.model

import com.notessync.app.security.ProtectionState

/** A revision/access snapshot for an explicitly opened transformer, without text or files. */
data class TransformAccess(val id: Long, val syncId: String, val isDeleted: Boolean, val isPurged: Boolean,
    val isProtected: Boolean, val notebookId: Long?, val notebookProtected: Boolean, val updatedAt: Long, val notebookUpdatedAt: Long) {
    fun allowed(session: ProtectionState) = NoteAccess(id, syncId, isDeleted, isPurged, isProtected, notebookId, notebookProtected).allowed(id, syncId, session)
}
