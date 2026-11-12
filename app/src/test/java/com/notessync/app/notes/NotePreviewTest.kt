package com.notessync.app.notes

import com.notessync.app.notes.text.notePreviewText
import org.junit.Assert.*
import org.junit.Test

class NotePreviewTest {
    @Test fun `bounded previews never leave half a surrogate pair at the cut`() {
        val source = "a".repeat(1999) + "😀" + "tail".repeat(1000)
        assertEquals("a".repeat(1999), notePreviewText(source))
        assertEquals("", notePreviewText("😀tail", 1))
        assertEquals("small", notePreviewText("small"))
        assertTrue(source.endsWith("tail"))
    }
}
