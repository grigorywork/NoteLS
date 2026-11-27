package com.notessync.app.data.local.model

/** Scalar projection: analytics never loads note bodies, formatting, tables or photos. */
data class AnalyticsEntry(
    val id: Long = 0,
    val title: String = "",
    val notebookId: Long? = null,
    val isTask: Boolean = false,
    val isCompleted: Boolean = false,
    val updatedAt: Long = 0,
    val taskCompletedAt: Long? = null,
    val taskElapsedMillis: Long = 0,
    val taskColor: String = "violet",
    val isDeleted: Boolean = false,
    val isPurged: Boolean = false,
    val isProtected: Boolean = false,
    val taskDueAt: Long? = null,
    val syncId: String = ""
)

data class AnalyticsNotebook(val id: Long, val name: String, val isProtected: Boolean = false, val isDeleted: Boolean = false, val isDiary: Boolean = false)
