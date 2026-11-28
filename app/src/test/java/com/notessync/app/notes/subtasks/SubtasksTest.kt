package com.notessync.app.notes.subtasks

import org.junit.Assert.*
import org.junit.Test

class SubtasksTest {
    @Test fun `typing retains an externally paused clock and removal while an explicit local restart wins`() {
        val old = Subtask("one", "Old", startedAt = 100)
        val pause = old.copy(elapsedMillis = 900, startedAt = null)
        val merged = Subtasks.merge(listOf(old.copy(text = "Draft")), listOf(old), listOf(pause)).single()
        assertEquals("Draft", merged.text); assertNull(merged.startedAt); assertEquals(900L, merged.elapsedMillis)
        assertTrue(Subtasks.merge(listOf(old.copy(text = "Draft")), listOf(old), emptyList()).isEmpty())
        assertEquals(200L, Subtasks.merge(listOf(old.copy(startedAt = 200)), listOf(old), listOf(pause)).single().startedAt)
    }
    @Test fun `history changes text and structure without resurrecting an externally stopped clock`() {
        val old = Subtask("one", "Old", startedAt = 100)
        val edited = old.copy(text = "New")
        val fresh = edited.copy(startedAt = null, elapsedMillis = 500)
        val undone = Subtasks.history(listOf(fresh), listOf(edited), listOf(old), 1000).single()
        assertEquals("Old", undone.text); assertNull(undone.startedAt); assertEquals(500L, undone.elapsedMillis)
        val restored = Subtasks.history(emptyList(), emptyList(), listOf(old), 1000).single()
        assertNull(restored.startedAt); assertEquals(900L, restored.elapsedMillis)
    }
    @Test fun `limits reject duplicate or malformed identity overflow and invalid finished generations`() {
        val valid = Subtask("a", countdownFinishedAt = 500)
        assertEquals(listOf(valid), Subtasks.checked(listOf(valid)))
        for (rows in listOf(listOf(valid, valid), listOf(valid.copy(id = "../bad")),
            listOf(valid.copy(text = "x".repeat(2001))), listOf(valid.copy(elapsedMillis = -1)),
            listOf(valid.copy(countdownEndAt = 600)), (0..100).map { Subtask("id$it") },
            (0..32).map { Subtask("id$it", "x".repeat(2000)) })) {
            try { Subtasks.checked(rows); fail("Accepted invalid subtask data") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `pause saturates clock extremes and finishing metadata follows external state without losing typed text`() {
        assertEquals(Long.MAX_VALUE, Subtask("a", elapsedMillis = Long.MAX_VALUE, startedAt = 1).paused(1000).elapsedMillis)
        val old = Subtask("a", "Old", countdownMillis = 100, countdownEndAt = 200)
        val finished = old.copy(countdownMillis = 0, countdownEndAt = null, countdownFinishedAt = 200)
        val merged = Subtasks.merge(listOf(old.copy(text = "Typed")), listOf(old), listOf(finished)).single()
        assertEquals("Typed", merged.text); assertEquals(200L, merged.countdownFinishedAt); assertNull(merged.countdownEndAt)
        assertEquals(0L, merged.countdownMillis)
    }
    @Test fun `external completion retains accrued time despite an unsaved local restart and keeps draft text`() {
        val before=Subtask("a","Old",elapsedMillis=100)
        val local=before.copy(text="Draft",startedAt=200)
        val completed=before.copy(completed=true,elapsedMillis=700,countdownMillis=500)
        val merged=Subtasks.merge(listOf(local),listOf(before),listOf(completed)).single()
        assertTrue(merged.completed);assertEquals(700L,merged.elapsedMillis);assertEquals(500L,merged.countdownMillis)
        assertEquals("Draft",merged.text);assertNull(merged.startedAt);assertNull(merged.countdownEndAt)
    }
}
