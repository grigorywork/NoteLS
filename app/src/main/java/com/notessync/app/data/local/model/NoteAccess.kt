package com.notessync.app.data.local.model

import com.notessync.app.security.ProtectionState

/** Media observes access without loading note text, formatting or attachment bytes. */
data class NoteAccess(val id: Long, val syncId: String, val isDeleted: Boolean, val isPurged: Boolean,
    val isProtected: Boolean, val notebookId: Long?, val notebookProtected: Boolean) {
    fun allowed(expectedId: Long, expectedSyncId: String, session: ProtectionState): Boolean =
        id == expectedId && syncId == expectedSyncId && !isDeleted && !isPurged &&
            (!isProtected || syncId in session.taskSyncIds) && (!notebookProtected || notebookId in session.notebookIds)
}
