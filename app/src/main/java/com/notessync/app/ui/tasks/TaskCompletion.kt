package com.notessync.app.ui.tasks

import com.notessync.app.data.local.model.nextChangeTimestamp

import com.notessync.app.data.local.entity.NoteEntity

/** Единый переход состояния задачи для списка, календаря и окна виджета. */
object TaskCompletion {

    fun toggle(note: NoteEntity, now: Long = System.currentTimeMillis()): NoteEntity {
        if (!note.isTask) return note
        if (!note.isCompleted) {
            val timer = TaskTimer.pause(note.taskElapsedMillis, note.taskTimerStartedAt, now)
            return note.copy(
                isCompleted = true,
                taskCompletedAt = now,
                taskElapsedMillis = timer.elapsedMillis,
                taskTimerStartedAt = null,
                taskCountdownMillis = TaskTimer.countdownRemaining(
                    note.taskCountdownMillis,
                    note.taskCountdownEndAt,
                    now
                ),
                taskCountdownEndAt = null,
                reminderAt = null,
                updatedAt = nextChangeTimestamp(note.updatedAt, now),
                dirty = true
            )
        }
        return note.copy(
            isCompleted = false,
            taskCompletedAt = null,
            updatedAt = nextChangeTimestamp(note.updatedAt, now),
            dirty = true
        )
    }
}
