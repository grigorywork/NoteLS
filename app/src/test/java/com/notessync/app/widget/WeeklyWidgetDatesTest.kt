package com.notessync.app.widget

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeeklyWidgetDatesTest {
    @Test fun `ISO weeks keep Monday across leap days and year boundaries`() {
        assertEquals(LocalDate.of(2020, 12, 28), WeeklyWidgetProvider.monday(LocalDate.of(2021, 1, 3)))
        assertEquals(LocalDate.of(2021, 1, 4), WeeklyWidgetProvider.monday(LocalDate.of(2021, 1, 3), 1))
        assertEquals(LocalDate.of(2024, 2, 26), WeeklyWidgetProvider.monday(LocalDate.of(2024, 2, 29)))
    }
    @Test fun `week offsets are bounded even for corrupt extreme values`() {
        val today = LocalDate.of(2026, 11, 13); val base = WeeklyWidgetProvider.monday(today)
        assertEquals(base.plusWeeks(520), WeeklyWidgetProvider.monday(today, Int.MAX_VALUE))
        assertEquals(base.minusWeeks(520), WeeklyWidgetProvider.monday(today, Int.MIN_VALUE))
    }
}
