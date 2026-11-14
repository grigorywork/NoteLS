package com.notessync.app.data.local.model

/** One small JOIN projection; even a protected title never leaves SQLite in plaintext here. */
data class NoteAlarmSnapshot(val id: Long, val syncId: String, val title: String, val hidden: Boolean,
    val isTask: Boolean, val isCompleted: Boolean, val isDeleted: Boolean, val isPurged: Boolean,
    val reminderAt: Long?, val taskCountdownEndAt: Long?)

internal const val NOTE_ALARM_SELECT = "SELECT n.id, n.syncId, " +
    "CASE WHEN n.isProtected OR COALESCE(b.isProtected,0) THEN '' ELSE SUBSTR(n.title,1,256) END AS title, " +
    "(n.isProtected OR COALESCE(b.isProtected,0)) AS hidden, n.isTask, n.isCompleted, n.isDeleted, n.isPurged, " +
    "n.reminderAt, n.taskCountdownEndAt FROM notes n LEFT JOIN notebooks b ON b.id = n.notebookId "
