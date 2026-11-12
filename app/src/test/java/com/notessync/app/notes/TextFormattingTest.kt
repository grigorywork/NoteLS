package com.notessync.app.notes

import com.notessync.app.notes.text.*
import org.junit.Assert.*
import org.junit.Test

class TextFormattingTest {
    private fun style() = TextFormatting().apply(1, 4, FormatKind.COLOR, "FFE53935")
    @Test fun `round trip preserves independent styles`() {
        val fmt = style().apply(2, 4, FormatKind.UNDERLINE, "1").apply(0, 4, FormatKind.SIZE, "24")
        assertEquals(fmt, TextFormatting.decode(fmt.encode(), 5))
    }
    @Test fun `partial replacement keeps both outside colors`() {
        val fmt = TextFormatting().apply(0, 6, FormatKind.COLOR, "FFE53935").apply(2, 4, FormatKind.COLOR, "FF1E88E5")
        assertEquals(3, fmt.marks.size); assertEquals("FF1E88E5", fmt.at(3)[FormatKind.COLOR]); assertEquals("FFE53935", fmt.at(5)[FormatKind.COLOR])
    }
    @Test fun `insertion inside range extends it`() {
        assertEquals(listOf(FormatMark(1, 6, "COLOR", "FFE53935")), style().edited("abcde", "abXXcde", 2, 2).marks)
    }
    @Test fun `replace then delete preserves other ranges`() {
        val fmt = style().edited("abcde", "aZe", 1, 4)
        assertEquals(listOf(FormatMark(1, 2, "COLOR", "FFE53935")), fmt.marks)
        assertTrue(fmt.edited("aZe", "ae", 1, 2).marks.isEmpty())
    }
    @Test fun `repeated letters use actual selection`() {
        assertEquals(listOf(FormatMark(3, 4, "UNDERLINE", "1")), TextFormatting().apply(2, 3, FormatKind.UNDERLINE, "1").edited("aaa", "aaaa", 0, 0).marks)
    }
    @Test fun `pending style changes inserted characters only`() {
        val fmt = style().edited("abcde", "abcZde", 3, 3, mapOf(FormatKind.COLOR to "FF1E88E5", FormatKind.SIZE to "32"))
        assertEquals("FF1E88E5", fmt.at(3, false)[FormatKind.COLOR]); assertEquals("32", fmt.at(3, false)[FormatKind.SIZE])
        assertEquals("FFE53935", fmt.at(4, false)[FormatKind.COLOR])
    }
    @Test fun `turning style off preserves old formatting`() {
        val fmt = style().edited("abcde", "abcdXe", 4, 4, mapOf(FormatKind.COLOR to null))
        assertEquals(style(), fmt); assertNull(fmt.at(4, false)[FormatKind.COLOR])
    }
    @Test fun `IME fallback adjusts the actual changed region`() {
        assertEquals(5, TextFormatting().apply(0, 4, FormatKind.SIZE, "20").edited("word!", "words!", 0, 0).marks.single().end)
    }
    @Test fun `malformed and future metadata stays harmless`() {
        assertTrue(TextFormatting.decode("broken", 4).marks.isEmpty())
        assertTrue(TextFormatting.decode("{\"version\":9,\"marks\":[]}", 4).marks.isEmpty())
        val raw = TextFormatting(marks = listOf(FormatMark(-1, 2, "SIZE", "24"), FormatMark(0, 9, "SIZE", "24"),
            FormatMark(0, 2, "SIZE", "999"), FormatMark(0, 2, "FONT", "remote-url"), FormatMark(0, 2, "COLOR", "00FFFFFF"),
            FormatMark(0, 2, "FUTURE", "hello"), FormatMark(0, 2, "UNDERLINE", "1")))
        assertEquals(listOf(FormatMark(0, 2, "UNDERLINE", "1")), TextFormatting.decode(raw.encode(), 4).marks)
    }
    @Test fun `overlapping imported property uses last mark`() {
        val raw = TextFormatting(marks = listOf(FormatMark(0, 6, "SIZE", "24"), FormatMark(2, 4, "SIZE", "32"), FormatMark(0, 6, "UNDERLINE", "1")))
        val fmt = TextFormatting.decode(raw.encode(), 6)
        assertEquals("24", fmt.at(1)[FormatKind.SIZE]); assertEquals("32", fmt.at(3)[FormatKind.SIZE]); assertEquals("24", fmt.at(5)[FormatKind.SIZE])
    }
    @Test fun `adjacent equivalent marks merge`() {
        var fmt = TextFormatting(); repeat(500) { fmt = fmt.apply(it, it + 1, FormatKind.SIZE, "20") }
        assertEquals(listOf(FormatMark(0, 500, "SIZE", "20")), fmt.marks)
    }
    @Test fun `separate line colors survive list prefixes`() {
        val fmt = TextFormatting().apply(0, 3, FormatKind.COLOR, "FFE53935").apply(4, 7, FormatKind.COLOR, "FF1E88E5")
            .editedLines("abc\ndef", "- abc\n- def")
        assertEquals("FFE53935", fmt.at(3)[FormatKind.COLOR]); assertEquals("FF1E88E5", fmt.at(9)[FormatKind.COLOR])
        assertEquals(11, fmt.marks.maxOf { it.end })
    }
    @Test fun `surrogate pair range stays valid after multiline insertion`() {
        val fmt = TextFormatting().apply(1, 3, FormatKind.BOLD, "1").edited("а😀\nб", "а😀\nновое б", 4, 4)
        assertEquals(FormatMark(1, 3, "BOLD", "1"), fmt.marks.single())
    }
    @Test fun `range bound preserves existing formatting and fallback typing`() {
        val fmt = TextFormatting(marks = (0 until TextFormatting.MAX_MARKS).map { FormatMark(it * 2, it * 2 + 1, "COLOR", "FFE53935") })
        val old = "a".repeat(TextFormatting.MAX_MARKS * 2)
        assertThrows(FormattingLimitReached::class.java) { fmt.edited(old, "b$old", 0, 0, mapOf(FormatKind.BOLD to "1")) }
        val next = fmt.edited(old, "b$old", 0, 0)
        assertEquals(TextFormatting.MAX_MARKS, next.marks.size); assertEquals(next, TextFormatting.decode(next.encode(), old.length + 1))
    }
}
