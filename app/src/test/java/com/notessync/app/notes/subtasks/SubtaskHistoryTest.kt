package com.notessync.app.notes.subtasks

import com.notessync.app.notes.history.*
import com.notessync.app.notes.text.TextFormatting
import org.junit.Assert.*
import org.junit.Test

class SubtaskHistoryTest {
    private fun snapshot(rows: List<Subtask>) = EditorSnapshot("Title", "Body", TextFormatting(), EditorCursor(), subtasks = rows)
    @Test fun `subtask-only steps undo redo without changing main text or live timing`() {
        val before = Subtask("a", "Before", startedAt = 100)
        val after = before.copy(text = "After")
        val h = EditorHistory(); assertEquals(HistoryResult.RECORDED, h.record(snapshot(listOf(before)), snapshot(listOf(after)), EditKind.ATOMIC))
        val current = after.copy(startedAt = null, elapsedMillis = 300)
        val undone = h.undo(snapshot(listOf(current)))!!
        assertEquals("Body", undone.content); assertEquals("Before", undone.subtasks.single().text)
        assertNull(undone.subtasks.single().startedAt); assertEquals(300L, undone.subtasks.single().elapsedMillis)
        val redone = h.redo(undone)!!; assertEquals("After", redone.subtasks.single().text); assertNull(redone.subtasks.single().startedAt)
    }
    @Test fun `structural history is memory bounded and restored running rows remain paused`() {
        val row = Subtask("a", "Long text", startedAt = 1)
        val h = EditorHistory(maxBytes = 1)
        assertEquals(HistoryResult.TOO_LARGE, h.record(snapshot(emptyList()), snapshot(listOf(row)), EditKind.ATOMIC))
        assertFalse(h.canUndo)
        val real = EditorHistory(); real.record(snapshot(listOf(row)), snapshot(emptyList()), EditKind.ATOMIC)
        val restored = real.undo(snapshot(emptyList()))!!.subtasks.single(); assertNull(restored.startedAt)
        assertTrue(restored.elapsedMillis > 0)
    }
}
