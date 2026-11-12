package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ParagraphEditTest {
    private fun all(text: String) = TextFieldValue(text, TextRange(0, text.length))
    private fun enter(text: String) = MarkdownEdit.handleEnter(TextFieldValue(text, TextRange(text.length)), TextFieldValue("$text\n", TextRange(text.length + 1))).text
    @Test fun `numbering replaces other list types without duplicating prefixes`() {
        val value = MarkdownEdit.toggleListKind(all("- one\n- [x] two\nthree"), MarkdownEdit.ListKind.NUMBER)
        assertEquals("1. one\n2. two\n3. three", value.text); assertEquals(TextRange(0, value.text.length), value.selection)
        assertEquals("one\ntwo\nthree", MarkdownEdit.toggleListKind(value, MarkdownEdit.ListKind.NUMBER).text)
    }
    @Test fun `letter and number continuation cross ordinal boundaries`() {
        assertEquals("z) item\naa) ", enter("z) item")); assertEquals("Z) item\nAA) ", enter("Z) item"))
        assertEquals("19. item\n20. ", enter("19. item"))
    }
    @Test fun `empty ordered items exit without another newline`() { assertEquals("", enter("5. ")); assertEquals("", enter("aa) ")) }
    @Test fun `ordinary English sentences remain prose`() {
        assertNull(ListMarkers.parse("2026 год")); assertNull(ListMarkers.parse("https://example.org"))
        assertNull(ListMarkers.parse("Note. Important")); assertNull(ListMarkers.parse("note. important"))
        assertEquals("Note. Important\n", enter("Note. Important"))
    }
    @Test fun `mixed checklist conversion retains checked states`() {
        assertEquals("- [x] done\n- [ ] plain\n- [X] also done", MarkdownEdit.toggleListKind(all("- [x] done\nplain\n- [X] also done"), MarkdownEdit.ListKind.CHECKLIST).text)
    }
    @Test fun `selection ending at newline excludes following paragraph`() {
        assertEquals("- one\ntwo", MarkdownEdit.toggleListKind(TextFieldValue("one\ntwo", TextRange(0, 4)), MarkdownEdit.ListKind.BULLET).text)
    }
    @Test fun `leading empty line targets itself`() {
        val value = TextFieldValue("\nnext", TextRange(0)); assertEquals("- \nnext", MarkdownEdit.toggleListKind(value, MarkdownEdit.ListKind.BULLET).text)
    }
    @Test fun `heading levels replace instead of stacking`() {
        val value = MarkdownEdit.toggleLinePrefix(all("## title\n# another"), "### ")
        assertEquals("### title\n### another", value.text); assertEquals("title\nanother", MarkdownEdit.toggleLinePrefix(value, "### ").text)
    }
    @Test fun `reverse selection includes both selected paragraphs`() {
        assertEquals("a) one\nb) two\nthree", MarkdownEdit.toggleListKind(TextFieldValue("one\ntwo\nthree", TextRange(7, 0)), MarkdownEdit.ListKind.LETTER).text)
    }
    @Test fun `ordinal bound does not create duplicate continuation`() {
        assertEquals("999999. last\n", enter("999999. last")); assertEquals("zzzz) last\n", enter("zzzz) last"))
    }
}
