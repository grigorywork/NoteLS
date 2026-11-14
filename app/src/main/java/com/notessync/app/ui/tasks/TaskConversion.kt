package com.notessync.app.ui.tasks

import com.notessync.app.data.local.entity.NoteEntity

/** Called only on a fresh, authorized row. Ordinary-note fields, document and relationships stay intact. */
internal fun NoteEntity.toOrdinaryNote(timestamp: Long) = copy(isTask = false, taskPriority = 0, taskDueAt = null,
    isCompleted = false, taskCompletedAt = null, taskElapsedMillis = 0, taskTimerStartedAt = null,
    taskCountdownMillis = 0, taskCountdownEndAt = null, isProtected = false, passwordHash = null,
    passwordSalt = null, updatedAt = timestamp, dirty = true)
