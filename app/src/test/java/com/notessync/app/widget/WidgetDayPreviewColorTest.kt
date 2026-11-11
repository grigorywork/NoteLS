package com.notessync.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Этап 33: строки предпросмотра виджета с цветовой разметкой задач. */
class WidgetDayPreviewColorTest {

    @Test
    fun `задача получает цвет заметка остаётся нейтральной`() {
        val entries = listOf(
            WidgetDayEntry("Позвонить", isTask = true, isCompleted = false, taskColor = "blue"),
            WidgetDayEntry("Мысли дня", isTask = false, isCompleted = false)
        )
        val lines = WidgetDayPreview.lines(entries)
        assertEquals("□ Позвонить", lines[0].text)
        assertEquals("blue", lines[0].colorKey)
        assertEquals("Мысли дня", lines[1].text)
        assertNull(lines[1].colorKey)
    }

    @Test
    fun `выполненная задача сохраняет цвет и галочку`() {
        val lines = WidgetDayPreview.lines(
            listOf(WidgetDayEntry("Сделано", isTask = true, isCompleted = true, taskColor = "mint"))
        )
        assertEquals("☑ Сделано", lines[0].text)
        assertEquals("mint", lines[0].colorKey)
    }

    @Test
    fun `счётчик остатка без цвета`() {
        val entries = (1..5).map {
            WidgetDayEntry("Задача $it", isTask = true, isCompleted = false, taskColor = "pink")
        }
        val lines = WidgetDayPreview.lines(entries)
        assertEquals(4, lines.size)
        assertEquals("+2", lines.last().text)
        assertNull(lines.last().colorKey)
    }

    @Test
    fun `format по-прежнему отдаёт плоский текст`() {
        val entries = listOf(
            WidgetDayEntry("А", isTask = true, isCompleted = false, taskColor = "blue"),
            WidgetDayEntry("Б", isTask = false, isCompleted = false)
        )
        assertEquals("□ А\nБ", WidgetDayPreview.format(entries))
        assertEquals("", WidgetDayPreview.format(emptyList()))
    }
}
