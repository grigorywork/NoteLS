package com.notessync.app.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskTimerTest {
    @Test
    fun pausedTimerKeepsStoredElapsedTime() {
        assertEquals(5_000L, TaskTimer.elapsedAt(5_000L, null, 100_000L))
    }

    @Test
    fun runningTimerAddsTimeSinceStart() {
        assertEquals(8_500L, TaskTimer.elapsedAt(5_000L, 10_000L, 13_500L))
    }

    @Test
    fun startAndPauseAccumulateElapsedTime() {
        val started = TaskTimer.toggle(2_000L, null, 10_000L)
        assertEquals(2_000L, started.elapsedMillis)
        assertEquals(10_000L, started.startedAt)

        val paused = TaskTimer.toggle(started.elapsedMillis, started.startedAt, 14_250L)
        assertEquals(6_250L, paused.elapsedMillis)
        assertNull(paused.startedAt)
    }

    @Test
    fun resetClearsElapsedTimeAndRunningState() {
        val reset = TaskTimer.reset()
        assertEquals(0L, reset.elapsedMillis)
        assertNull(reset.startedAt)
    }

    @Test
    fun formattingSupportsLongDurations() {
        assertEquals("00:00:00", TaskTimer.format(0L))
        assertEquals("01:02:03", TaskTimer.format(3_723_000L))
        assertEquals("27:00:05", TaskTimer.format(97_205_000L))
    }

    @Test
    fun backwardClockChangeDoesNotReduceElapsedTime() {
        assertEquals(4_000L, TaskTimer.elapsedAt(4_000L, 20_000L, 10_000L))
    }
}
