package com.notessync.app.ui.tasks

import com.notessync.app.data.local.entity.NoteEntity

/** Keep a notification/list command when this editor only edited document text, without losing explicit local controls. */
internal fun mergeExternalTaskTiming(local: NoteEntity, previous: NoteEntity?, fresh: NoteEntity?): NoteEntity {
    if (previous == null || fresh == null || !previous.isTask || !local.isTask || !fresh.isTask) return local
    val unchangedCompletion = local.isCompleted == previous.isCompleted && local.taskCompletedAt == previous.taskCompletedAt
    val externalCompletion = unchangedCompletion && fresh.isCompleted
    val watch = externalCompletion || (local.taskElapsedMillis == previous.taskElapsedMillis && local.taskTimerStartedAt == previous.taskTimerStartedAt)
    val countdown = externalCompletion || (local.taskCountdownMillis == previous.taskCountdownMillis && local.taskCountdownEndAt == previous.taskCountdownEndAt)
    return local.copy(
        isCompleted = if (unchangedCompletion) fresh.isCompleted else local.isCompleted,
        taskCompletedAt = if (unchangedCompletion) fresh.taskCompletedAt else local.taskCompletedAt,
        taskElapsedMillis = if (watch) fresh.taskElapsedMillis else local.taskElapsedMillis,
        taskTimerStartedAt = if (watch) fresh.taskTimerStartedAt else local.taskTimerStartedAt,
        taskCountdownMillis = if (countdown) fresh.taskCountdownMillis else local.taskCountdownMillis,
        taskCountdownEndAt = if (countdown) fresh.taskCountdownEndAt else local.taskCountdownEndAt,
        reminderAt = if (externalCompletion || local.reminderAt == previous.reminderAt) fresh.reminderAt else local.reminderAt
    )
}
