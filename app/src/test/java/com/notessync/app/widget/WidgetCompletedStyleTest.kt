package com.notessync.app.widget

import com.notessync.app.settings.WidgetCompletedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCompletedStyleTest {

    @Test
    fun `green style enables only green color`() {
        assertTrue(WidgetCompletedStyle.GREEN.usesGreen)
        assertFalse(WidgetCompletedStyle.GREEN.usesStrike)
    }

    @Test
    fun `strike style enables only strikethrough`() {
        assertFalse(WidgetCompletedStyle.STRIKE.usesGreen)
        assertTrue(WidgetCompletedStyle.STRIKE.usesStrike)
    }

    @Test
    fun `combined style enables both decorations`() {
        assertTrue(WidgetCompletedStyle.BOTH.usesGreen)
        assertTrue(WidgetCompletedStyle.BOTH.usesStrike)
    }

    @Test
    fun `completed task preview carries completion metadata`() {
        val lines = WidgetDayPreview.lines(
            listOf(
                WidgetDayEntry("Готово", isTask = true, isCompleted = true),
                WidgetDayEntry("В работе", isTask = true, isCompleted = false),
                WidgetDayEntry("Обычная", isTask = false, isCompleted = false)
            )
        )
        assertTrue(lines[0].isCompleted)
        assertEquals("☑ Готово", lines[0].text)
        assertFalse(lines[1].isCompleted)
        assertFalse(lines[2].isCompleted)
    }
}
