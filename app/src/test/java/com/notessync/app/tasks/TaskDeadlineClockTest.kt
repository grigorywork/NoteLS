package com.notessync.app.tasks

import com.notessync.app.ui.tasks.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TaskDeadlineClockTest {
    @Test fun `one boundary wakeup selects the nearest future deadline then midnight and ignores stale deadlines`() {
        val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli(); val zone = ZoneId.of("UTC")
        assertEquals(now + 101, TaskDeadlines.nextRefresh(now, now + 100, zone))
        assertEquals(now + 1, TaskDeadlines.nextRefresh(now, now, zone))
        assertEquals(now + 1_001, TaskDeadlines.nextRefresh(now + 1_000, now + 100, zone, capturedAt = now))
        assertEquals(TaskDeadlines.dayBounds(now, zone).second, TaskDeadlines.nextRefresh(now, now - 1, zone))
        assertEquals(TaskDeadlines.dayBounds(now, zone).second, TaskDeadlines.nextRefresh(now, Long.MAX_VALUE, zone))
    }
    @Test fun `invalid external choices normalize and deadline bounds reject invalid epochs`() {
        assertEquals(TaskFilters(), TaskFilters(priority = 99, notebookId = -1, tagId = 0).normalized())
        assertTrue(TaskDeadlines.valid(null)); assertTrue(TaskDeadlines.valid(0))
        assertFalse(TaskDeadlines.valid(Long.MIN_VALUE)); assertFalse(TaskDeadlines.valid(Long.MAX_VALUE))
    }
}
