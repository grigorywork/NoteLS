package com.notessync.app.data.subtasks

/** Only parents of at most20 currently posted child alerts are observed; no document/password/text. */
data class SubtaskAlertPrivacy(val id: Long, val syncId: String, val isProtected: Boolean,
    val isDeleted: Boolean, val isPurged: Boolean, val isTask: Boolean, val isCompleted: Boolean,
    val notebookId: Long?, val notebookSyncId: String?, val notebookProtected: Boolean,
    val notebookDeleted: Boolean, val diary: Boolean)
