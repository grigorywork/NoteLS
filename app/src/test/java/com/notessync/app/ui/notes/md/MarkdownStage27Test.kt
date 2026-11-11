package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 27: режимы ввода жирный/курсив без видимых маркеров, подсветка активного
 * стиля под курсором, выполненные пункты — зелёные и зачёркнутые, скрытие
 * выполненных в превью.
 */
class MarkdownStage27Test {

    // ---------- Режимы ввода (toggleWrap без выделения) ----------

    @Test
    fun `курсор перед закрывающим маркером — выход из режима без вставки`() {
        // Текст «**жирный**», курсор вплотную перед закрывающим «**» (позиция 8).
        val v = TextFieldValue("**жирный**обычный", TextRange(8))
        val r = MarkdownEdit.toggleWrap(v, "**")
        assertEquals("**жирный**обычный", r.text) // текст не изменился
        assertEquals(TextRange(10), r.selection)  // курсор вышел за маркер
    }

    @Test
    fun `пустая пара маркеров вокруг курсора удаляется целиком`() {
        val v = TextFieldValue("а****б", TextRange(3))
        val r = MarkdownEdit.toggleWrap(v, "**")
        assertEquals("аб", r.text)
        assertEquals(TextRange(1), r.selection)
    }

    @Test
    fun `вход в режим — пара маркеров, курсор между ними`() {
        val v = TextFieldValue("текст", TextRange(5))
        val r = MarkdownEdit.toggleWrap(v, "*")
        assertEquals("текст**", r.text)
        assertEquals(TextRange(6), r.selection)
    }

    // ---------- Подсветка активного стиля ----------

    @Test
    fun `курсор внутри пары — стиль активен, снаружи — нет`() {
        val text = "**жирный** обычный"
        assertTrue(MarkdownEdit.isInsideWrap(text, 5, "**"))
        assertFalse(MarkdownEdit.isInsideWrap(text, 10, "**"))
        assertFalse(MarkdownEdit.isInsideWrap(text, 5, "*"))
    }

    @Test
    fun `активность определяется в пределах строки`() {
        val text = "**жирный**\nобычный ** ещё"
        assertFalse(MarkdownEdit.isInsideWrap(text, 15, "**"))
        assertTrue(MarkdownEdit.isInsideWrap(text, 21, "**"))
    }

    // ---------- Выполненные пункты: стиль и скрытие ----------

    @Test
    fun `выполненный пункт в редакторе зелёный и зачёркнутый`() {
        val result = MarkdownVisualTransformation().filter(AnnotatedString("- [x] готово"))
        assertEquals("☑ готово", result.text.text)
        val strike = result.text.spanStyles.firstOrNull {
            it.item.textDecoration == TextDecoration.LineThrough
        }
        assertTrue("Должен быть зачёркнутый span", strike != null)
        val green = result.text.spanStyles.firstOrNull {
            it.item.color == MarkdownLite.CompletedColor
        }
        assertTrue("Должен быть зелёный span", green != null)
    }

    @Test
    fun `превью скрывает выполненные и хранит соответствие строк`() {
        val text = "- [ ] первый\n- [x] готово\n- [ ] второй"
        val parsed = MarkdownLite.parse(text, hideCompleted = true)
        assertEquals("☐ первый\n☐ второй", parsed.text)
        val map = MarkdownLite.visibleSourceLines(text, hideCompleted = true)
        assertEquals(2, map.size)
        assertEquals(0, map[0])
        assertEquals(2, map[1])
    }

    @Test
    fun `превью без скрытия оставляет соответствие один к одному`() {
        val text = "раз\n- [x] готово\nдва"
        val parsed = MarkdownLite.parse(text)
        assertEquals(3, parsed.text.count { it == '\n' } + 1)
        val map = MarkdownLite.visibleSourceLines(text, hideCompleted = false)
        assertEquals(listOf(0, 1, 2), map.toList())
    }
}
