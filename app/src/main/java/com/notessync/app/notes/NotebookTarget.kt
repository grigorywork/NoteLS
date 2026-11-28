package com.notessync.app.notes

import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.ProtectionState

/** Identity carried by a new draft or an explicit destination choice, never just a reusable row ID. */
data class NotebookTarget(val id: Long, val syncId: String) {
    fun allows(book: NotebookEntity?, session: ProtectionState, allowDiary: Boolean = false): Boolean =
        id > 0 && syncId.isNotBlank() && book != null && book.id == id && book.syncId == syncId &&
            !book.isDeleted && (!book.isDiary || allowDiary) &&
            (!book.isProtected || id in session.notebookIds)
}
