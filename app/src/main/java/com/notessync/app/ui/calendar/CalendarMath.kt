package com.notessync.app.ui.calendar

import java.time.LocalDate
import java.time.YearMonth

data class CalendarCell(
    val date: LocalDate,
    val inMonth: Boolean
)

/** Чистая геометрия календаря: шесть недель, понедельник — первый день. */
object CalendarMath {
    const val CELL_COUNT = 42

    fun grid(month: YearMonth): List<CalendarCell> {
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        return (0 until CELL_COUNT).map { offset ->
            val date = start.plusDays(offset.toLong())
            CalendarCell(date, YearMonth.from(date) == month)
        }
    }

    fun initialSelection(month: YearMonth, today: LocalDate): LocalDate =
        if (YearMonth.from(today) == month) today else month.atDay(1)
}
