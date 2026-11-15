package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class CyrillicListsTest {
    private fun enter(text: String): TextFieldValue = MarkdownEdit.handleEnter(
        TextFieldValue(text, TextRange(text.length)), TextFieldValue("$text\n", TextRange(text.length + 1)))
    @Test fun `Russian letters continue including yo and multi-letter ordinals`() {
        assertEquals("а) один\nб) ", enter("а) один").text)
        assertEquals("е) один\nё) ", enter("е) один").text)
        assertEquals("ё) один\nж) ", enter("ё) один").text)
        assertEquals("я) один\nаа) ", enter("я) один").text)
        assertEquals("Я) один\nАА) ", enter("Я) один").text)
    }
    @Test fun `Cyrillic list conversion preserves content and selection and toggles off`() {
        val source = "- первая\n2. вторая\na) third"
        val result = MarkdownEdit.toggleListKind(TextFieldValue(source, TextRange(0, source.length)), MarkdownEdit.ListKind.LETTER, ListAlphabet.CYRILLIC)
        assertEquals("а) первая\nб) вторая\nв) third", result.text)
        assertEquals(TextRange(0, result.text.length), result.selection)
        assertEquals("первая\nвторая\nthird", MarkdownEdit.toggleListKind(result, MarkdownEdit.ListKind.LETTER, ListAlphabet.CYRILLIC).text)
    }
    @Test fun `empty Russian item exits and mixed alphabets never become lists`() {
        assertEquals("", enter("б) ").text)
        assertEquals("", enter("АА) ").text)
        assertNull(ListMarkers.parse("aб) mixed"))
        assertEquals("aб) mixed\n", enter("aб) mixed").text)
    }
    @Test fun `Latin documents continue identically regardless of Russian support`() {
        assertEquals("z) old\naa) ", enter("z) old").text)
        assertEquals("Z) old\nAA) ", enter("Z) old").text)
        assertEquals("999999. cap\n", enter("999999. cap").text)
        for (alphabet in ListAlphabet.entries) for (n in listOf(1, 26, 33, 34, 1024, alphabet.maxOrdinal)) {
            val encoded = ListMarkers.prefix(MarkdownEdit.ListKind.LETTER, n, alphabet = alphabet)
            assertEquals(n, ListMarkers.parse(encoded)!!.ordinal)
        }
    }
}
