package com.notessync.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetDayPreviewTest {
    @Test
    fun emptyDayHasNoMarkerOrText() {
        assertEquals("", WidgetDayPreview.format(emptyList()))
    }

    @Test
    fun tasksUseTextCheckboxesInsteadOfDots() {
        val text = WidgetDayPreview.format(
            listOf(
                WidgetDayEntry("Open", isTask = true, isCompleted = false),
                WidgetDayEntry("Done", isTask = true, isCompleted = true),
                WidgetDayEntry("Note", isTask = false, isCompleted = false)
            )
        )
        assertEquals("□ Open\n☑ Done\nNote", text)
    }

    @Test
    fun overflowShowsNumberOfRemainingEntries() {
        val entries = (1..6).map { WidgetDayEntry("Task $it", true, false) }
        assertEquals("□ Task 1\n□ Task 2\n□ Task 3\n+3", WidgetDayPreview.format(entries))
    }

    @Test
    fun multilineAndBlankTitlesAreSafeForSingleLineCells() {
        val text = WidgetDayPreview.format(
            listOf(
                WidgetDayEntry("First\nSecond", false, false),
                WidgetDayEntry("", true, false)
            )
        )
        assertEquals("First\n□ Задача", text)
    }
}
