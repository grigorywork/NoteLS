package com.notessync.app.tasks.running

import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.ui.tasks.mergeExternalTaskTiming
import org.junit.Test
import org.junit.Assert.*

class RunningClockTimeTest {
    @Test fun `clock arithmetic saturates extremes and handles a backwards wall clock without overflow`() {
        assertEquals(1000L, RunningClockTime.elapsed(500, 1000, 1500))
        assertEquals(0L, RunningClockTime.elapsed(-99, 2000, 1500))
        assertEquals(Long.MAX_VALUE, RunningClockTime.elapsed(10, Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, RunningClockTime.elapsed(Long.MAX_VALUE, 1, 10))
        assertEquals(Long.MAX_VALUE, RunningClockTime.remaining(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(0L, RunningClockTime.remaining(10, 20))
    }
    @Test fun `text save keeps an external pause or completion without losing local body edits`() {
        val old = NoteEntity(isTask = true, content = "old", taskTimerStartedAt = 100, taskCountdownEndAt = 500)
        val local = old.copy(content = "new local body")
        val paused = old.copy(taskTimerStartedAt = null, taskElapsedMillis = 50)
        val merged = mergeExternalTaskTiming(local, old, paused)
        assertEquals("new local body", merged.content); assertNull(merged.taskTimerStartedAt); assertEquals(50L, merged.taskElapsedMillis)
        val done = paused.copy(isCompleted = true, taskCompletedAt = 200, taskCountdownEndAt = null)
        val completed = mergeExternalTaskTiming(local, old, done)
        assertTrue(completed.isCompleted); assertEquals(200L, completed.taskCompletedAt); assertNull(completed.taskCountdownEndAt)
    }
    @Test fun `explicit editor clock control or uncheck is retained while unrelated fresh controls are merged`() {
        val old = NoteEntity(isTask = true, taskElapsedMillis = 10, taskTimerStartedAt = 100, taskCountdownEndAt = 500)
        val localReset = old.copy(taskElapsedMillis = 0, taskTimerStartedAt = null)
        val fresh = old.copy(taskCountdownEndAt = null, taskCountdownMillis = 20)
        val merged = mergeExternalTaskTiming(localReset, old, fresh)
        assertEquals(0L, merged.taskElapsedMillis); assertNull(merged.taskTimerStartedAt); assertNull(merged.taskCountdownEndAt)
        assertEquals(20L, merged.taskCountdownMillis)
        val done = old.copy(isCompleted = true, taskCompletedAt = 200, taskTimerStartedAt = null)
        assertFalse(mergeExternalTaskTiming(done.copy(isCompleted = false, taskCompletedAt = null), done, done).isCompleted)
        val ordinary = old.copy(isTask = false); assertEquals(ordinary, mergeExternalTaskTiming(ordinary, old, fresh))
    }
}
