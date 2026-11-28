package com.notessync.app.notes.subtasks

import androidx.compose.ui.text.input.TextFieldValue
import com.notessync.app.notes.history.*
import com.notessync.app.notes.text.TextFormatting
import com.notessync.app.ui.notes.EditorDocument
import com.notessync.app.ui.notes.EditorMessage
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

    @Test fun `undo rejects remote count overflow without losing rows or its retryable step`() {
        assertCountLimit(backwards = true)
    }

    @Test fun `redo rejects remote count overflow without losing rows or its retryable step`() {
        assertCountLimit(backwards = false)
    }

    private fun assertCountLimit(backwards: Boolean) {
        val local = Subtask("local", "Restored local child")
        val retained = (0 until 99).map { Subtask("base$it", "Saved child $it") }
        val h = EditorHistory()
        if (backwards) {
            h.record(snapshot(listOf(local) + retained), snapshot(retained), EditKind.ATOMIC)
        } else {
            h.record(snapshot(retained), snapshot(retained + local), EditKind.ATOMIC)
            assertEquals(retained, h.undo(snapshot(retained + local))!!.subtasks)
        }
        val remote = Subtask("remote", "Added on another device")
        val current = snapshot(Subtasks.checked(retained + remote))
        assertRejectedWithoutMutation(h, current, backwards)

        // Another device frees one slot; retry the same history entry and retain its new child.
        val freed = current.copy(subtasks = current.subtasks.filterNot { it.id == "base98" })
        val restored = restore(h, freed, backwards)!!
        assertEquals(if (backwards) listOf(local) + freed.subtasks else freed.subtasks + local, restored.subtasks)
        assertEquals(remote, restored.subtasks.single { it.id == remote.id })
        assertEquals(!backwards, h.canUndo)
        assertEquals(backwards, h.canRedo)
        assertEquals(1, h.stepCount)
    }

    @Test fun `undo rejects remote text growth past the total limit and can retry after capacity is freed`() {
        assertTextLimit(backwards = true)
    }

    @Test fun `redo rejects remote text growth past the total limit and can retry after capacity is freed`() {
        assertTextLimit(backwards = false)
    }

    private fun assertTextLimit(backwards: Boolean) {
        val local = Subtask("local", "L".repeat(2000))
        val retained = (0 until 31).map { Subtask("base$it", "x".repeat(2000)) }
        val full = Subtasks.checked(listOf(local) + retained + Subtask("remote"))
        val short = full.map { if (it.id == local.id) it.copy(text = "") else it }
        val h = EditorHistory()
        if (backwards) {
            h.record(snapshot(full), snapshot(short), EditKind.ATOMIC)
        } else {
            h.record(snapshot(short), snapshot(full), EditKind.ATOMIC)
            assertEquals(short, h.undo(snapshot(full))!!.subtasks)
        }
        // Each row remains valid; the one remote character makes the proposed restore 64,001.
        val current = snapshot(Subtasks.checked(short.map { if (it.id == "remote") it.copy(text = "r") else it }))
        assertRejectedWithoutMutation(h, current, backwards)

        val freed = current.copy(subtasks = current.subtasks.map {
            if (it.id == "base0") it.copy(text = it.text.dropLast(1)) else it
        })
        val restored = restore(h, freed, backwards)!!
        assertEquals(freed.subtasks.map { if (it.id == local.id) local else it }, restored.subtasks)
        assertEquals(64_000, restored.subtasks.sumOf { it.text.length })
        assertEquals("r", restored.subtasks.single { it.id == "remote" }.text)
        assertEquals(!backwards, h.canUndo)
        assertEquals(backwards, h.canRedo)
        assertEquals(1, h.stepCount)
    }

    private fun restore(h: EditorHistory, current: EditorSnapshot, backwards: Boolean) =
        if (backwards) h.undo(current) else h.redo(current)

    private fun assertRejectedWithoutMutation(h: EditorHistory, current: EditorSnapshot, backwards: Boolean) {
        val original = current.copy(subtasks = current.subtasks.toList())
        val bytes = h.estimatedBytes
        val steps = h.stepCount
        val canUndo = h.canUndo
        val canRedo = h.canRedo
        try {
            restore(h, current, backwards)
            fail("Accepted a subtask history restoration over the limit")
        } catch (_: SubtaskHistoryLimitReached) { }
        assertEquals(original, current)
        assertEquals(bytes, h.estimatedBytes)
        assertEquals(steps, h.stepCount)
        assertEquals(canUndo, h.canUndo)
        assertEquals(canRedo, h.canRedo)
    }

    @Test fun `editor reports a history capacity limit and preserves the document and retry action`() {
        val messages = mutableListOf<EditorMessage>()
        val d = EditorDocument(true, { true }, { messages.add(it) })
        d.titleField = TextFieldValue("Title")
        d.contentField = TextFieldValue("Unsaved body")
        val local = Subtask("local", "Deleted child")
        d.subtasks = listOf(local) + (0 until 99).map { Subtask("base$it") }
        d.removeSubtask(local.id)
        val remote = Subtask("remote", "Remote child")
        d.subtasks = d.subtasks + remote
        val current = d.subtasks
        val version = d.historyRestoreVersion

        d.undo()

        assertEquals(listOf(EditorMessage.SUBTASK_LIMIT), messages)
        assertEquals(current, d.subtasks)
        assertEquals("Title", d.title)
        assertEquals("Unsaved body", d.contentField.text)
        assertTrue(d.canUndo)
        assertFalse(d.canRedo)
        assertEquals(version, d.historyRestoreVersion)
        d.subtasks = d.subtasks.filterNot { it.id == "base98" }
        d.undo()
        assertEquals(100, d.subtasks.size)
        assertEquals(local, d.subtasks.first())
        assertEquals(remote, d.subtasks.single { it.id == remote.id })
        assertFalse(d.canUndo)
        assertTrue(d.canRedo)
        assertEquals(version + 1, d.historyRestoreVersion)
        assertEquals(listOf(EditorMessage.SUBTASK_LIMIT), messages)
    }
}
