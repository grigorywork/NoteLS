package com.notessync.app.notes.audit

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.notessync.app.notes.text.*
import com.notessync.app.ui.notes.md.MarkdownLite
import org.junit.Assert.*
import org.junit.Test

/** Exact card rendering path: clip the document for display while preserving overlapping rich ranges. */
class NotePreviewFormattingRegressionTest {
    @Test fun `bold range extending beyond card preview still styles visible text`() {
        val source = "long note ".repeat(500)
        val encoded = TextFormatting().apply(0, source.length, FormatKind.BOLD, "1").encode()
        val preview = notePreviewText(source)
        val rendered = MarkdownLite.parse(preview, formatting = encoded, clipFormatting = true)
        assertTrue("Visible prefix must retain its bold range", rendered.spanStyles.any {
            it.start == 0 && it.end == preview.length && it.item.fontWeight == FontWeight.Bold
        })
    }
    @Test fun `paragraph alignment extending beyond card preview remains visible`() {
        val source = "long paragraph ".repeat(400)
        val encoded = TextFormatting().apply(0, source.length, FormatKind.ALIGN, "CENTER").encode()
        val rendered = MarkdownLite.parse(notePreviewText(source), formatting = encoded, clipFormatting = true)
        assertTrue("Visible paragraph must retain its alignment", rendered.paragraphStyles.any { it.item.textAlign == TextAlign.Center })
    }
    @Test fun `strict complete decoding stays strict while preview clips Unicode ranges without mutating source`() {
        val raw = TextFormatting().apply(1, 4000, FormatKind.BOLD, "1").encode()
        val text = "😀 abc"
        assertTrue(TextFormatting.decode(raw, text.length).marks.isEmpty())
        val clipped = TextFormatting.decodePreview(raw, text)
        assertEquals(0, clipped.marks.single().start)
        assertEquals(text.length, clipped.marks.single().end)
        assertEquals(4000, TextFormatting.decode(raw, 5000).marks.single().end)
    }

}
