package com.notessync.app.data.local.model

import androidx.room.Embedded
import com.notessync.app.security.ProtectionState

/** Committed timestamps and access flags; no document text or credentials. */
data class EditorDatesSnapshot(
    @Embedded val access: NoteAccess,
    val createdAt: Long,
    val updatedAt: Long,
    val notebookDeleted: Boolean,
    val notebookMissing: Boolean
) {
    fun allowed(id: Long, syncId: String, session: ProtectionState): Boolean =
        access.allowed(id, syncId, session) && !notebookDeleted && !notebookMissing
}
