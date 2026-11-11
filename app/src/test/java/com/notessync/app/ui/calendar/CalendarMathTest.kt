package com.notessync.app.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarMathTest {

    @Test
    fun `grid always contains six Monday-first weeks`() {
        val cells = CalendarMath.grid(YearMonth.of(2026, 11))
        assertEquals(42, cells.size)
        assertEquals(DayOfWeek.MONDAY, cells.first().date.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, cells.last().date.dayOfWeek)
        assertEquals(6, cells.chunked(7).size)
    }

    @Test
    fun `January 2026 starts with previous Monday and ends on Sunday`() {
        val cells = CalendarMath.grid(YearMonth.of(2026, 1))
        assertEquals(LocalDate.of(2025, 12, 29), cells.first().date)
        assertEquals(LocalDate.of(2026, 2, 8), cells.last().date)
        assertEquals(31, cells.count { it.inMonth })
    }

    @Test
    fun `month starting Monday has no leading previous-month cells`() {
        val cells = CalendarMath.grid(YearMonth.of(2026, 6))
        assertEquals(LocalDate.of(2026, 6, 1), cells.first().date)
        assertTrue(cells.first().inMonth)
        assertEquals(LocalDate.of(2026, 7, 12), cells.last().date)
        assertFalse(cells.last().inMonth)
    }

    @Test
    fun `current month initially selects today`() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals(today, CalendarMath.initialSelection(YearMonth.from(today), today))
    }

    @Test
    fun `another month initially selects its first day`() {
        val today = LocalDate.of(2026, 11, 11)
        val month = YearMonth.of(2026, 12)
        assertEquals(month.atDay(1), CalendarMath.initialSelection(month, today))
    }
}
