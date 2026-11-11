package com.notessync.app.ui.tasks

import com.notessync.app.data.local.entity.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionTest {

    private val runningTask = NoteEntity(
        id = 7,
        isTask = true,
        taskElapsedMillis = 5_000L,
        taskTimerStartedAt = 90_000L,
        taskCountdownMillis = 600_000L,
        taskCountdownEndAt = 700_000L,
        reminderAt = 650_000L,
        updatedAt = 1L,
        dirty = false
    )

    @Test
    fun `completion freezes stopwatch and countdown and clears reminder`() {
        val result = TaskCompletion.toggle(runningTask, now = 100_000L)
        assertTrue(result.isCompleted)
        assertEquals(100_000L, result.taskCompletedAt)
        assertEquals(15_000L, result.taskElapsedMillis)
        assertNull(result.taskTimerStartedAt)
        assertEquals(600_000L, result.taskCountdownMillis)
        assertNull(result.taskCountdownEndAt)
        assertNull(result.reminderAt)
        assertEquals(100_000L, result.updatedAt)
        assertTrue(result.dirty)
    }

    @Test
    fun `uncompletion clears completion timestamp without restarting timers`() {
        val completed = TaskCompletion.toggle(runningTask, now = 100_000L)
        val result = TaskCompletion.toggle(completed, now = 200_000L)
        assertFalse(result.isCompleted)
        assertNull(result.taskCompletedAt)
        assertNull(result.taskTimerStartedAt)
        assertNull(result.taskCountdownEndAt)
        assertEquals(15_000L, result.taskElapsedMillis)
        assertEquals(600_000L, result.taskCountdownMillis)
        assertEquals(200_000L, result.updatedAt)
    }

    @Test
    fun `expired countdown is frozen at zero`() {
        val result = TaskCompletion.toggle(runningTask, now = 800_000L)
        assertEquals(0L, result.taskCountdownMillis)
        assertNull(result.taskCountdownEndAt)
    }

    @Test
    fun `ordinary note is not changed`() {
        val note = NoteEntity(title = "Заметка")
        assertSame(note, TaskCompletion.toggle(note, now = 100_000L))
    }
}
