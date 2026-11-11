package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 25 — работа с текстом: активная строка сырьём, границы курсива,
 * ***жирный курсив***, чекбокс «- [X]», автопродолжение списков по Enter,
 * циклические кнопки списков без наложения префиксов.
 */
class MarkdownStage25Test {

    // ---------- Трансформация: активная строка ----------

    private fun transform(raw: String, activeLine: IntRange? = null) =
        MarkdownVisualTransformation(activeLine).filter(AnnotatedString(raw))

    @Test
    fun `активная строка показывает маркеры как есть`() {
        val raw = "**жирный**\nвторой **б**"
        val result = transform(raw, activeLine = 0..10)
        // Первая строка (с курсором) — сырьём, вторая — WYSIWYG.
        assertEquals("**жирный**\nвторой б", result.text.text)
    }

    @Test
    fun `в активной строке маппинг курсора один к одному`() {
        val raw = "**жирный**\nтекст"
        val result = transform(raw, activeLine = 0..10)
        for (i in 0..10) {
            assertEquals(i, result.offsetMapping.originalToTransformed(i))
        }
    }

    @Test
    fun `неактивные строки остаются wysiwyg`() {
        val raw = "первая\n**жирный**"
        val result = transform(raw, activeLine = 0..6)
        assertEquals("первая\nжирный", result.text.text)
    }

    // ---------- Трансформация: границы и новые формы ----------

    @Test
    fun `звёздочки умножения не становятся курсивом`() {
        val result = transform("2 * 3 * 4")
        assertEquals("2 * 3 * 4", result.text.text)
    }

    @Test
    fun `курсив с пробелом у маркера не применяется`() {
        val result = transform("* не курсив*")
        assertEquals("* не курсив*", result.text.text)
    }

    @Test
    fun `жирный курсив тремя звёздочками`() {
        val result = transform("***важно***")
        assertEquals("важно", result.text.text)
        val span = result.text.spanStyles.first()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(FontStyle.Italic, span.item.fontStyle)
    }

    @Test
    fun `чекбокс с большой буквы рисуется значком`() {
        assertEquals("☑ готово", transform("- [X] готово").text.text)
    }

    // ---------- Превью (MarkdownLite): те же правила ----------

    @Test
    fun `превью не превращает умножение в курсив`() {
        assertEquals("2 * 3 * 4", MarkdownLite.parse("2 * 3 * 4").text)
    }

    @Test
    fun `превью понимает чекбокс с большой буквы и переключает его`() {
        assertEquals("☑ готово", MarkdownLite.parse("- [X] готово").text)
        assertEquals("- [ ] готово", MarkdownLite.toggleCheckbox("- [X] готово", 0))
        assertTrue(MarkdownLite.hasCheckboxes("- [X] готово"))
    }

    // ---------- Автопродолжение списков по Enter ----------

    private fun enterOn(prevText: String, cursor: Int): TextFieldValue {
        val prev = TextFieldValue(prevText, TextRange(cursor))
        val next = TextFieldValue(
            prevText.substring(0, cursor) + "\n" + prevText.substring(cursor),
            TextRange(cursor + 1)
        )
        return MarkdownEdit.handleEnter(prev, next)
    }

    @Test
    fun `enter продолжает список новым пунктом`() {
        val result = enterOn("- раз", 5)
        assertEquals("- раз\n- ", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun `enter на пустом пункте снимает маркер без новой строки`() {
        val result = enterOn("- раз\n- ", 8)
        assertEquals("- раз\n", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test
    fun `enter продолжает чек-лист пустым чекбоксом`() {
        val result = enterOn("- [x] сделано", 13)
        assertEquals("- [x] сделано\n- [ ] ", result.text)
        assertEquals(TextRange(20), result.selection)
    }

    @Test
    fun `enter в середине пункта делит его на два`() {
        val result = enterOn("- раз два", 5)
        assertEquals("- раз\n-  два", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun `enter на обычной строке не меняет ввод`() {
        val result = enterOn("просто текст", 6)
        assertEquals("просто\n текст", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    // ---------- Циклические кнопки списков ----------

    @Test
    fun `кнопка списка ставит и снимает маркер`() {
        val v = TextFieldValue("раз", TextRange(3))
        val listed = MarkdownEdit.toggleListKind(v, MarkdownEdit.ListKind.BULLET)
        assertEquals("- раз", listed.text)
        val plain = MarkdownEdit.toggleListKind(
            listed.copy(selection = TextRange(0, listed.text.length)),
            MarkdownEdit.ListKind.BULLET
        )
        assertEquals("раз", plain.text)
    }

    @Test
    fun `чек-лист заменяет маркер списка а не накладывает`() {
        val v = TextFieldValue("- раз", TextRange(5))
        val checked = MarkdownEdit.toggleListKind(v, MarkdownEdit.ListKind.CHECKLIST)
        assertEquals("- [ ] раз", checked.text)
    }

    @Test
    fun `список заменяет маркер чек-листа а не накладывает`() {
        val v = TextFieldValue("- [ ] раз", TextRange(9))
        val bullet = MarkdownEdit.toggleListKind(v, MarkdownEdit.ListKind.BULLET)
        assertEquals("- раз", bullet.text)
    }

    @Test
    fun `кнопка чек-листа снимает отмеченный чекбокс`() {
        val v = TextFieldValue("- [x] готово", TextRange(12))
        val plain = MarkdownEdit.toggleListKind(v, MarkdownEdit.ListKind.CHECKLIST)
        assertEquals("готово", plain.text)
    }
}
