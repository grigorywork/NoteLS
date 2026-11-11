package com.notessync.app.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Этап 31: вычисления аналитики выполненных задач. */
class AnalyticsMathTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    private fun point(
        day: LocalDate,
        hour: Int = 12,
        elapsed: Long = 0L,
        color: String = "violet",
        notebookId: Long? = null
    ): TaskStatPoint = TaskStatPoint(
        noteId = 1L,
        title = "Задача",
        completedAt = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        elapsedMillis = elapsed,
        color = color,
        notebookId = notebookId
    )

    @Test
    fun `byDay считает выполнения и заполняет пустые дни нулями`() {
        val points = listOf(
            point(today), point(today), point(today.minusDays(2))
        )
        val result = AnalyticsMath.byDay(points, 3, today, zone)
        assertEquals(3, result.size)
        assertEquals(today.minusDays(2) to 1, result[0])
        assertEquals(today.minusDays(1) to 0, result[1])
        assertEquals(today to 2, result[2])
    }

    @Test
    fun `неделя начинается с понедельника`() {
        // 10.11.2026 — вторник.
        assertEquals(LocalDate.of(2026, 11, 9), AnalyticsMath.weekStart(today))
        assertEquals(LocalDate.of(2026, 11, 9), AnalyticsMath.weekStart(LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 9), AnalyticsMath.weekStart(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `byWeek группирует по неделям`() {
        val points = listOf(
            point(today), point(today.minusDays(1)), // эта неделя
            point(today.minusWeeks(1)) // прошлая
        )
        val result = AnalyticsMath.byWeek(points, 2, today, zone)
        assertEquals(2, result.size)
        assertEquals(1, result[0].second)
        assertEquals(2, result[1].second)
    }

    @Test
    fun `текущая серия считается от сегодня`() {
        val points = listOf(point(today), point(today.minusDays(1)), point(today.minusDays(2)))
        assertEquals(3, AnalyticsMath.currentStreak(points, today, zone))
    }

    @Test
    fun `серия не ломается если сегодня ещё ничего не выполнено`() {
        val points = listOf(point(today.minusDays(1)), point(today.minusDays(2)))
        assertEquals(2, AnalyticsMath.currentStreak(points, today, zone))
    }

    @Test
    fun `серия ноль если вчера и сегодня пусто`() {
        val points = listOf(point(today.minusDays(3)))
        assertEquals(0, AnalyticsMath.currentStreak(points, today, zone))
        assertEquals(0, AnalyticsMath.currentStreak(emptyList(), today, zone))
    }

    @Test
    fun `пропуск дня обрывает текущую серию`() {
        val points = listOf(point(today), point(today.minusDays(2)))
        assertEquals(1, AnalyticsMath.currentStreak(points, today, zone))
    }

    @Test
    fun `рекордная серия ищет максимум по истории`() {
        val points = listOf(
            point(today.minusDays(10)), point(today.minusDays(9)),
            point(today.minusDays(8)), point(today.minusDays(7)), // серия 4
            point(today), point(today.minusDays(1)) // серия 2
        )
        assertEquals(4, AnalyticsMath.bestStreak(points, zone))
        assertEquals(0, AnalyticsMath.bestStreak(emptyList(), zone))
    }

    @Test
    fun `несколько задач в один день не удлиняют серию`() {
        val points = listOf(point(today), point(today), point(today.minusDays(1)))
        assertEquals(2, AnalyticsMath.bestStreak(points, zone))
    }

    @Test
    fun `byHour распределяет по часам суток`() {
        val points = listOf(point(today, hour = 9), point(today, hour = 9), point(today, hour = 21))
        val hours = AnalyticsMath.byHour(points, zone)
        assertEquals(24, hours.size)
        assertEquals(2, hours[9])
        assertEquals(1, hours[21])
        assertEquals(3, hours.sum())
    }

    @Test
    fun `byColor и byNotebook сортируются по убыванию`() {
        val points = listOf(
            point(today, color = "blue", notebookId = 2L),
            point(today, color = "violet", notebookId = 1L),
            point(today, color = "violet", notebookId = null)
        )
        assertEquals(listOf("violet" to 2, "blue" to 1), AnalyticsMath.byColor(points))
        assertEquals(listOf(1L to 1, null to 1, 2L to 1).size, AnalyticsMath.byNotebook(points).size)
        assertEquals(1, AnalyticsMath.byNotebook(points).first().second)
    }

    @Test
    fun `bestDay возвращает день с максимумом`() {
        val points = listOf(point(today), point(today), point(today.minusDays(1)))
        assertEquals(today to 2, AnalyticsMath.bestDay(points, zone))
        assertNull(AnalyticsMath.bestDay(emptyList(), zone))
    }

    @Test
    fun `среднее время игнорирует задачи без секундомера`() {
        val points = listOf(
            point(today, elapsed = 100L), point(today, elapsed = 300L), point(today, elapsed = 0L)
        )
        assertEquals(200L, AnalyticsMath.averageElapsed(points))
        assertEquals(0L, AnalyticsMath.averageElapsed(emptyList()))
        assertEquals(400L, AnalyticsMath.totalElapsed(points))
    }

    @Test
    fun `самая быстрая и долгая только среди задач с временем`() {
        val points = listOf(
            point(today, elapsed = 0L), point(today, elapsed = 500L), point(today, elapsed = 100L)
        )
        assertEquals(100L, AnalyticsMath.fastest(points)?.elapsedMillis)
        assertEquals(500L, AnalyticsMath.slowest(points)?.elapsedMillis)
        assertNull(AnalyticsMath.fastest(listOf(point(today, elapsed = 0L))))
    }
}
