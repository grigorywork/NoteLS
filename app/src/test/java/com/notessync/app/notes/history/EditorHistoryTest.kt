package com.notessync.app.notes.history

import com.notessync.app.notes.text.*
import org.junit.Assert.*
import org.junit.Test

class EditorHistoryTest {
    private fun state(text: String, start: Int = text.length, end: Int = start,
                      format: TextFormatting = TextFormatting(), title: String = "") =
        EditorSnapshot(title, text, format, EditorCursor(content = EditorSelection(start, end)))

    @Test fun `typing groups, pauses split and undo redo preserve cursor`() {
        var time = 0L
        val h = EditorHistory(clockMillis = { time })
        val empty = state(""); val a = state("a"); val ab = state("ab")
        h.record(empty, a, EditKind.INPUT); time += 100; h.record(a, ab, EditKind.INPUT)
        assertEquals(1, h.stepCount)
        assertEquals(empty, h.undo(ab)); assertEquals(ab, h.redo(empty))
        time += 1001
        val abc = state("abc"); h.record(ab, abc, EditKind.INPUT)
        assertEquals(2, h.stepCount); assertEquals(ab, h.undo(abc))
    }

    @Test fun `backspace and forward delete group without joining insertion`() {
        for (backwards in listOf(true, false)) {
            val h = EditorHistory()
            val original = state("abcd", if (backwards) 4 else 0)
            val first = state(if (backwards) "abc" else "bcd", if (backwards) 3 else 0)
            val second = state(if (backwards) "ab" else "cd", if (backwards) 2 else 0)
            h.record(original, first, EditKind.INPUT); h.record(first, second, EditKind.INPUT)
            assertEquals(1, h.stepCount); assertEquals(original, h.undo(second))
            assertEquals(second, h.redo(original))
        }
    }

    @Test fun `selection movement is not a step but breaks typing group`() {
        val h = EditorHistory(); val a = state("a"); val end = state("ab"); val start = state("ab", 0)
        h.record(a, end, EditKind.INPUT); h.breakGroup()
        assertEquals(HistoryResult.UNCHANGED, h.record(end, start, EditKind.INPUT))
        val next = state("xab", 1); h.record(start, next, EditKind.INPUT)
        assertEquals(start, h.undo(next)); assertEquals(a, h.undo(start))
    }

    @Test fun `formatting and reverse selection restore exactly`() {
        val h = EditorHistory(); val fmt = TextFormatting().apply(0, 4, FormatKind.BOLD, "1")
        val original = state("text", 4, 0); val styled = original.copy(formatting = fmt)
        h.record(original, styled, EditKind.ATOMIC)
        assertEquals(original, h.undo(styled)); assertEquals(styled, h.redo(original))
    }

    @Test fun `pending style is undoable and not merged with following text`() {
        val h = EditorHistory(); val original = state("")
        val pending = original.copy(cursor = original.cursor.copy(pending = mapOf(FormatKind.BOLD to "1")))
        h.record(original, pending, EditKind.ATOMIC)
        val next = state("x", format = TextFormatting().apply(0, 1, FormatKind.BOLD, "1"))
            .copy(cursor = pending.cursor.copy(content = EditorSelection(1)))
        h.record(pending, next, EditKind.INPUT)
        assertEquals(pending, h.undo(next)); assertEquals(original, h.undo(pending))
    }

    @Test fun `IME intermediate replacement stays in one group with Unicode`() {
        val h = EditorHistory(); val original = state("😀", 2)
        val first = state("😀ni"); val second = state("😀你")
        h.record(original, first, EditKind.COMPOSITION); h.record(first, second, EditKind.COMPOSITION)
        assertEquals(1, h.stepCount); assertEquals(original, h.undo(second)); assertEquals(second, h.redo(original))
    }

    @Test fun `cancelled IME composition leaves no empty undo step`() {
        val h = EditorHistory(); val original = state("text"); val composing = state("textni")
        h.record(original, composing, EditKind.COMPOSITION)
        h.record(composing, original, EditKind.COMPOSITION)
        assertFalse(h.canUndo); assertEquals(0L, h.estimatedBytes)
    }

    @Test fun `new edit invalidates redo and no-op does not`() {
        val h = EditorHistory(); val a = state("a"); val b = state("b"); val c = state("c")
        h.record(a, b, EditKind.ATOMIC); h.undo(b)
        h.record(a, a, EditKind.ATOMIC); assertTrue(h.canRedo)
        h.record(a, c, EditKind.ATOMIC); assertFalse(h.canRedo); assertEquals(a, h.undo(c))
    }

    @Test fun `title and content share one ordered history`() {
        val h = EditorHistory(); val a = state("body").copy(cursor = EditorCursor(field = EditorField.TITLE))
        val b = a.copy(title = "title", cursor = a.cursor.copy(title = EditorSelection(5)))
        val c = b.copy(content = "body!", cursor = b.cursor.copy(field = EditorField.CONTENT, content = EditorSelection(5)))
        h.record(a, b, EditKind.ATOMIC); h.record(b, c, EditKind.ATOMIC)
        assertEquals(b, h.undo(c)); assertEquals(a, h.undo(b)); assertEquals(b, h.redo(a)); assertEquals(c, h.redo(b))
    }

    @Test fun `large document uses small deltas and both stacks share the budget`() {
        val h = EditorHistory(maxSteps = 3, maxBytes = 2048)
        var current = state("x".repeat(2_000_000))
        repeat(10) {
            val next = state(current.content + "z")
            h.record(current, next, EditKind.ATOMIC); current = next
        }
        assertEquals(3, h.stepCount); assertTrue(h.estimatedBytes <= 2048)
        val bytes = h.estimatedBytes
        repeat(3) { current = h.undo(current)!! }
        assertFalse(h.canUndo); assertTrue(h.canRedo); assertEquals(bytes, h.estimatedBytes)
        repeat(3) { current = h.redo(current)!! }
        assertEquals(2_000_010, current.content.length)
    }

    @Test fun `oversized edit resets history without retaining the document`() {
        val h = EditorHistory(maxBytes = 1024); val a = state("a"); val b = state("b")
        h.record(a, b, EditKind.ATOMIC); h.undo(b)
        assertEquals(HistoryResult.TOO_LARGE, h.record(a, state("x".repeat(1000)), EditKind.ATOMIC))
        assertFalse(h.canUndo); assertFalse(h.canRedo); assertEquals(0L, h.estimatedBytes)
    }

    @Test fun `memory eviction counts formatting and removes only reachable oldest steps`() {
        val h = EditorHistory(maxBytes = 1600)
        val plain = state("text"); val bold = plain.copy(formatting = TextFormatting().apply(0, 4, FormatKind.BOLD, "1"))
        val italic = bold.copy(formatting = bold.formatting.apply(0, 4, FormatKind.ITALIC, "1"))
        val both = italic.copy(formatting = italic.formatting.apply(0, 4, FormatKind.UNDERLINE, "1"))
        h.record(plain, bold, EditKind.ATOMIC); h.record(bold, italic, EditKind.ATOMIC); h.record(italic, both, EditKind.ATOMIC)
        assertTrue(h.estimatedBytes <= 1600)
        var current = h.undo(both)!!
        assertEquals(italic, current)
        while (h.canUndo) current = h.undo(current)!!
        h.clear(); assertEquals(0, h.stepCount); assertEquals(0L, h.estimatedBytes)
    }

    @Test fun `groups stop at 256 UTF16 units and newline boundaries`() {
        val h = EditorHistory(); var current = state("")
        repeat(257) { val next = state(current.content + "x"); h.record(current, next, EditKind.INPUT); current = next }
        assertEquals(2, h.stepCount)
        val line = state(current.content + "\n"); h.record(current, line, EditKind.INPUT)
        val text = state(line.content + "a"); h.record(line, text, EditKind.INPUT)
        assertEquals(line, h.undo(text)); assertEquals(current, h.undo(line))
    }
}
