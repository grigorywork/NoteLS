package com.notessync.app.data.local.model

/** Per-editor event projection. Body, title, formatting, passwords and changing update timestamps stay out. */
data class TaskMetadataSnapshot(val id: Long, val syncId: String, val isTask: Boolean, val isCompleted: Boolean,
    val taskElapsedMillis: Long, val taskTimerStartedAt: Long?, val taskCountdownMillis: Long,
    val taskCountdownEndAt: Long?, val taskCompletedAt: Long?, val reminderAt: Long?)
