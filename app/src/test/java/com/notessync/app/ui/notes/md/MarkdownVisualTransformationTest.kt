package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HF-1: WYSIWYG-трансформация редактора — маркеры скрыты, стили применены. */
class MarkdownVisualTransformationTest {

    private val transformation = MarkdownVisualTransformation()

    private fun transform(raw: String) =
        transformation.filter(AnnotatedString(raw))

    @Test
    fun `жирный отображается без звёздочек`() {
        val result = transform("обычный **жирный** текст")
        assertEquals("обычный жирный текст", result.text.text)
        val boldSpan = result.text.spanStyles.firstOrNull {
            it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold
        }
        assertTrue("Жирный span должен присутствовать", boldSpan != null)
    }

    @Test
    fun `курсив отображается без звёздочек`() {
        val result = transform("*курсив*")
        assertEquals("курсив", result.text.text)
    }

    @Test
    fun `чекбокс рисуется символом а не скобками`() {
        assertEquals("☐ купить молоко", transform("- [ ] купить молоко").text.text)
        assertEquals("☑ готово", transform("- [x] готово").text.text)
    }

    @Test
    fun `список рисуется точкой`() {
        assertEquals("• пункт", transform("- пункт").text.text)
    }

    @Test
    fun `заголовок без решётки`() {
        assertEquals("Заголовок", transform("# Заголовок").text.text)
    }

    @Test
    fun `текст без разметки не меняется`() {
        assertEquals("просто текст", transform("просто текст").text.text)
    }

    @Test
    fun `маппинг курсора туда-обратно согласован`() {
        val raw = "а **б** в"
        val result = transform(raw)
        // «б» в исходнике на позиции 4, в трансформе — на позиции 2.
        assertEquals(2, result.offsetMapping.originalToTransformed(4))
        assertEquals(4, result.offsetMapping.transformedToOriginal(2))
    }
}
