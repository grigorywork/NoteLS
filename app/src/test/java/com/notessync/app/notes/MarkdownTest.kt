package com.notessync.app.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import com.notessync.app.ui.notes.md.MarkdownEdit
import com.notessync.app.ui.notes.md.MarkdownLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    @Test
    fun `заголовок становится крупным жирным, маркер скрыт`() {
        val parsed = MarkdownLite.parse("# Привет")
        assertEquals("Привет", parsed.text)
        val span = parsed.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertTrue(span.item.fontSize.value > 1.4f) // em
    }

    @Test
    fun `жирный и курсив внутри строки`() {
        val parsed = MarkdownLite.parse("а **б** в *г*")
        assertEquals("а б в г", parsed.text)
        val bold = parsed.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("б", parsed.text.substring(bold.start, bold.end))
        val italic = parsed.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("г", parsed.text.substring(italic.start, italic.end))
    }

    @Test
    fun `список превращается в маркеры`() {
        val parsed = MarkdownLite.parse("- раз\n- два")
        assertEquals("• раз\n• два", parsed.text)
    }

    @Test
    fun `чек-боксы рисуются значками и число строк не меняется`() {
        val text = "- [ ] купить\nобычная\n- [x] сделано"
        val parsed = MarkdownLite.parse(text)
        assertEquals("☐ купить\nобычная\n☑ сделано", parsed.text)
        assertEquals(3, parsed.text.count { it == '\n' } + 1)
        assertTrue(MarkdownLite.hasCheckboxes(text))
    }

    @Test
    fun `переключение чек-бокса туда-обратно`() {
        val text = "- [ ] задача\nпросто текст"
        val done = MarkdownLite.toggleCheckbox(text, 0)
        assertEquals("- [x] задача\nпросто текст", done)
        assertEquals(text, MarkdownLite.toggleCheckbox(done!!, 0))
        // В строке без чек-бокса — ничего не происходит.
        assertNull(MarkdownLite.toggleCheckbox(text, 1))
    }

    @Test
    fun `незакрытый маркер остаётся текстом`() {
        val parsed = MarkdownLite.parse("звезда * одинокая")
        assertEquals("звезда * одинокая", parsed.text)
    }
}

class MarkdownEditTest {

    @Test
    fun `обёртка жирным ставится и снимается`() {
        val v = TextFieldValue("привет мир", TextRange(0, 6)) // «привет» выделен
        val bold = MarkdownEdit.toggleWrap(v, "**")
        assertEquals("**привет** мир", bold.text)
        assertEquals(TextRange(2, 8), bold.selection)

        val back = MarkdownEdit.toggleWrap(bold, "**")
        assertEquals("привет мир", back.text)
        assertEquals(TextRange(0, 6), back.selection)
    }

    @Test
    fun `без выделения маркеры вставляются, курсор между ними`() {
        val v = TextFieldValue("текст", TextRange(5))
        val r = MarkdownEdit.toggleWrap(v, "*")
        assertEquals("текст**", r.text)
        assertEquals(TextRange(6), r.selection)
    }

    @Test
    fun `префикс списка на несколько строк и обратно`() {
        val v = TextFieldValue("раз\nдва", TextRange(0, 7))
        val listed = MarkdownEdit.toggleLinePrefix(v, "- ")
        assertEquals("- раз\n- два", listed.text)
        // Снятие: снова выделяем обе строки целиком.
        val plain = MarkdownEdit.toggleLinePrefix(
            listed.copy(selection = TextRange(0, listed.text.length)), "- "
        )
        assertEquals("раз\nдва", plain.text)
    }

    @Test
    fun `чек-лист из пустой строки`() {
        val v = TextFieldValue("", TextRange(0))
        val r = MarkdownEdit.toggleLinePrefix(v, "- [ ] ")
        assertEquals("- [ ] ", r.text)
    }
}
