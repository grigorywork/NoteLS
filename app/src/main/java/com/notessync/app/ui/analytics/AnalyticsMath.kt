package com.notessync.app.ui.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Одна выполненная задача для статистики (Этап 31). */
data class TaskStatPoint(
    val noteId: Long,
    val title: String,
    val completedAt: Long,
    val elapsedMillis: Long,
    val color: String,
    val notebookId: Long?
)

/** Чистые вычисления аналитики: удобно тестировать без Android. */
object AnalyticsMath {

    fun dayOf(timeMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()

    /** Выполнения по дням: [days] дней, заканчивая [today] (включительно). */
    fun byDay(
        points: List<TaskStatPoint>,
        days: Int,
        today: LocalDate,
        zone: ZoneId
    ): List<Pair<LocalDate, Int>> {
        val counts = points.groupingBy { dayOf(it.completedAt, zone) }.eachCount()
        return (days - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            day to (counts[day] ?: 0)
        }
    }

    /** Выполнения по неделям (понедельник — первый день), ключ — начало недели. */
    fun byWeek(
        points: List<TaskStatPoint>,
        weeks: Int,
        today: LocalDate,
        zone: ZoneId
    ): List<Pair<LocalDate, Int>> {
        val counts = points.groupingBy { weekStart(dayOf(it.completedAt, zone)) }.eachCount()
        val thisWeek = weekStart(today)
        return (weeks - 1 downTo 0).map { offset ->
            val start = thisWeek.minusWeeks(offset.toLong())
            start to (counts[start] ?: 0)
        }
    }

    fun weekStart(day: LocalDate): LocalDate =
        day.minusDays((day.dayOfWeek.value - 1).toLong())

    /**
     * Текущая серия: сколько дней подряд есть выполненные задачи.
     * Сегодняшний день может быть ещё «пустым» — серия тогда считается от вчера.
     */
    fun currentStreak(points: List<TaskStatPoint>, today: LocalDate, zone: ZoneId): Int {
        val days = points.mapTo(mutableSetOf()) { dayOf(it.completedAt, zone) }
        if (days.isEmpty()) return 0
        var cursor = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** Рекордная серия дней подряд за всю историю. */
    fun bestStreak(points: List<TaskStatPoint>, zone: ZoneId): Int {
        val days = points.map { dayOf(it.completedAt, zone) }.toSortedSet()
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }

    /** Распределение выполнений по часам суток (24 значения). */
    fun byHour(points: List<TaskStatPoint>, zone: ZoneId): IntArray {
        val hours = IntArray(24)
        points.forEach { point ->
            val hour = Instant.ofEpochMilli(point.completedAt).atZone(zone).hour
            hours[hour]++
        }
        return hours
    }

    /** Разрез по цветам задач, по убыванию количества. */
    fun byColor(points: List<TaskStatPoint>): List<Pair<String, Int>> =
        points.groupingBy { it.color }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }

    /** Разрез по блокнотам (null — «Без блокнота»), по убыванию количества. */
    fun byNotebook(points: List<TaskStatPoint>): List<Pair<Long?, Int>> =
        points.groupingBy { it.notebookId }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }

    /** День с максимальным числом выполнений. */
    fun bestDay(points: List<TaskStatPoint>, zone: ZoneId): Pair<LocalDate, Int>? =
        points.groupingBy { dayOf(it.completedAt, zone) }.eachCount()
            .maxByOrNull { it.value }?.toPair()

    /** Среднее зафиксированное время среди задач, где секундомер использовался. */
    fun averageElapsed(points: List<TaskStatPoint>): Long {
        val tracked = points.filter { it.elapsedMillis > 0L }
        if (tracked.isEmpty()) return 0L
        return tracked.sumOf { it.elapsedMillis } / tracked.size
    }

    fun totalElapsed(points: List<TaskStatPoint>): Long =
        points.sumOf { it.elapsedMillis.coerceAtLeast(0L) }

    /** Самая быстрая задача среди имеющих зафиксированное время. */
    fun fastest(points: List<TaskStatPoint>): TaskStatPoint? =
        points.filter { it.elapsedMillis > 0L }.minByOrNull { it.elapsedMillis }

    fun slowest(points: List<TaskStatPoint>): TaskStatPoint? =
        points.filter { it.elapsedMillis > 0L }.maxByOrNull { it.elapsedMillis }
}
