package com.notessync.app.ui.notes.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.notessync.app.notes.text.*
import org.junit.Assert.*
import org.junit.Test

class FormattedTextTest {
    @Test fun `legacy markup and rich color retain cursor mapping`() {
        val raw = "а **б** в"
        val result = FormattedTextTransformation(TextFormatting().apply(4, 5, FormatKind.COLOR, "FFE53935")).filter(AnnotatedString(raw))
        assertEquals("а б в", result.text.text); assertEquals(2, result.offsetMapping.originalToTransformed(4)); assertEquals(4, result.offsetMapping.transformedToOriginal(2))
        assertTrue(result.text.spanStyles.any { it.start == 2 && it.end == 3 && it.item.color == androidx.compose.ui.graphics.Color(0xFFE53935L) })
        for (i in 0..raw.length) assertTrue(result.offsetMapping.originalToTransformed(i) in 0..result.text.length)
    }
    @Test fun `underline and strike combine in filtered preview`() {
        val raw = "- [x] готово\n- [ ] осталось"
        val fmt = TextFormatting().apply(0, raw.length, FormatKind.UNDERLINE, "1").apply(0, raw.length, FormatKind.STRIKE, "1")
        val result = formattedPreview(raw, fmt, true)
        assertEquals("☐ осталось", result.text)
        assertTrue(result.spanStyles.any { it.item.textDecoration?.contains(TextDecoration.Underline) == true && it.item.textDecoration?.contains(TextDecoration.LineThrough) == true })
    }
    @Test fun `underline does not spread completed strike to unfinished neighbour`() {
        val raw = "- [x] done\n- [ ] todo"
        val result = formattedPreview(raw, TextFormatting().apply(0, raw.length, FormatKind.UNDERLINE, "1"))
        val point = result.text.indexOf("todo")
        val decoration = result.spanStyles.last { it.start <= point && it.end > point }.item.textDecoration!!
        assertTrue(decoration.contains(TextDecoration.Underline)); assertFalse(decoration.contains(TextDecoration.LineThrough))
    }
    @Test fun `heading three renders without markers`() { assertEquals("Small\ntext", formattedPreview("### Small\ntext", TextFormatting()).text) }
    @Test fun `paragraph alignment retains exact checkbox offsets`() {
        val raw = "- [ ] first\nsecond"
        val fmt = TextFormatting().apply(0, 11, FormatKind.ALIGN, "CENTER").apply(0, 11, FormatKind.INDENT, "2")
        val result = FormattedTextTransformation(fmt).filter(AnnotatedString(raw))
        assertEquals("☐ first\u200Bsecond", result.text.text)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, result.text.paragraphStyles.first().item.textAlign)
        assertEquals(40f, result.text.paragraphStyles.first().item.textIndent!!.firstLine.value)
        assertEquals(6, result.offsetMapping.transformedToOriginal(2))
    }
    @Test fun `hidden completed lines retain source annotations and paragraph alignment`() {
        val raw = "- [x] done\nopen\nlast"
        val fmt = TextFormatting().apply(0, 10, FormatKind.ALIGN, "END").apply(11, 15, FormatKind.ALIGN, "CENTER")
        val result = formattedPreview(raw, fmt, true)
        assertEquals("open\u200Blast", result.text)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, result.paragraphStyles.first().item.textAlign)
        assertEquals("1", result.getStringAnnotations(SOURCE_LINE_ANNOTATION, 0, 0).last().item)
        assertEquals("2", result.getStringAnnotations(SOURCE_LINE_ANNOTATION, 5, 5).last().item)
    }
}
