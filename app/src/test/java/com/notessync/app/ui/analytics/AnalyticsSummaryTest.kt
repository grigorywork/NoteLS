package com.notessync.app.ui.analytics

import com.notessync.app.data.local.model.*
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsSummaryTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 12)
    private fun completed(id: Long, day: LocalDate = today, book: Long? = null, elapsed: Long = 1000) = AnalyticsEntry(
        id, "Task $id", book, isTask = true, isCompleted = true, taskCompletedAt = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), taskElapsedMillis = elapsed)
    private fun build(entries: List<AnalyticsEntry>, books: List<AnalyticsNotebook> = emptyList(), scope: AnalyticsScope = AnalyticsScope.All) =
        AnalyticsSummary.build(entries, books, scope, today, zone)

    @Test fun `general counts partition ordinary notes tasks completed and active for every scope`() {
        val books = listOf(AnalyticsNotebook(1, "Work"), AnalyticsNotebook(2, "Home"), AnalyticsNotebook(3, "Private", true))
        val entries = listOf(AnalyticsEntry(1, notebookId = 1), AnalyticsEntry(2, notebookId = 1, isTask = true),
            completed(3), AnalyticsEntry(4, notebookId = 2, isCompleted = true), completed(5).copy(isDeleted = true),
            completed(6).copy(isPurged = true), completed(7).copy(isProtected = true), completed(8, book = 3))
        val all = build(entries, books)
        assertEquals(4, all.totalEntries); assertEquals(2, all.noteCount); assertEquals(2, all.taskCount)
        assertEquals(1, all.totalCompleted); assertEquals(1, all.activeTasks)
        assertEquals(all.totalEntries, all.noteCount + all.taskCount)
        val work = build(entries, books, AnalyticsScope.Notebook(1))
        assertEquals(2, work.totalEntries); assertEquals(1, work.noteCount); assertEquals(1, work.activeTasks); assertEquals(0, work.totalCompleted)
        val unfiled = build(entries, books, AnalyticsScope.Unfiled)
        assertEquals(1, unfiled.totalEntries); assertEquals(1, unfiled.totalCompleted)
    }
    @Test fun `empty notebook remains selectable deleted scope falls back and orphan entries are unfiled`() {
        val books = listOf(AnalyticsNotebook(1, "Все блокноты"), AnalyticsNotebook(2, "Old", isDeleted = true), AnalyticsNotebook(3, "Empty"))
        val entries = listOf(completed(1, book = 1), completed(2, book = 2), AnalyticsEntry(3, notebookId = 999))
        val empty = build(entries, books, AnalyticsScope.Notebook(3))
        assertEquals(AnalyticsScope.Notebook(3), empty.scope); assertEquals(0, empty.totalEntries)
        assertEquals(AnalyticsScope.All, build(entries, books, AnalyticsScope.Notebook(2)).scope)
        assertEquals(2, build(entries, books, AnalyticsScope.Unfiled).totalEntries)
        assertEquals("Все блокноты", build(entries, books).byNotebook.first().first)
    }
    @Test fun `period windows exclude future completions and keep bounded charts and timer meaning`() {
        val entries = listOf(1L, 0L, -6L, -7L, -29L, -30L).mapIndexed { index, offset -> completed(index.toLong(), today.plusDays(offset)) } +
            AnalyticsEntry(20, isTask = true, taskElapsedMillis = 999_999)
        val result = build(entries)
        assertEquals(6, result.totalCompleted); assertEquals(2, result.completedLast7Days); assertEquals(4, result.completedLast30Days)
        assertEquals(6000L, result.totalElapsedMillis); assertEquals(1000L, result.averageElapsedMillis)
        assertEquals(14, result.byDay.size); assertEquals(8, result.byWeek.size); assertEquals(24, result.byHour.size)
    }
    @Test fun `only newest twenty rows are retained with deterministic equal-time ordering and normalized colors`() {
        val entries = (1L..200L).map { completed(it).copy(taskColor = if (it % 2L == 0L) "unknown" else "violet") }.reversed()
        val result = build(entries)
        assertEquals((200L downTo 181L).toList(), result.recentCompleted.map { it.id })
        assertEquals(listOf("violet" to 200), result.byColor)
    }
    @Test fun `protection change filter change or missing metadata hides cached private titles`() {
        val books = listOf(AnalyticsNotebook(1, "Work"), AnalyticsNotebook(2, "Deleted private", isProtected = true, isDeleted = true))
        val rows = listOf(completed(1, book = 1).copy(title = "Sensitive"), completed(2, book = 2))
        val old = build(rows, books)
        val protected = books.map { it.copy(isProtected = true) }
        val hidden = AnalyticsSummary.visible(old, AnalyticsScope.All, protected)
        assertTrue(hidden.loading); assertTrue(hidden.recentCompleted.isEmpty()); assertNull(hidden.fastestTitle)
        assertTrue(AnalyticsSummary.visible(old, AnalyticsScope.Unfiled, books).loading)
        assertTrue(AnalyticsSummary.visible(old, AnalyticsScope.All, null).loading)
        val fresh = build(rows, protected)
        assertEquals(0, fresh.totalEntries); assertFalse(AnalyticsSummary.visible(fresh, AnalyticsScope.All, protected).loading)
    }
}
