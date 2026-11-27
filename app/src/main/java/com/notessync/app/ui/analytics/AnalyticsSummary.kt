package com.notessync.app.ui.analytics

import com.notessync.app.data.local.model.AnalyticsEntry
import com.notessync.app.data.local.model.AnalyticsNotebook
import com.notessync.app.ui.tasks.taskColorOption
import java.time.LocalDate
import java.time.ZoneId
import java.util.PriorityQueue

sealed interface AnalyticsScope {
    data object All : AnalyticsScope
    data object Unfiled : AnalyticsScope
    data class Notebook(val id: Long) : AnalyticsScope
}

data class AnalyticsState(
    val loading: Boolean = true,
    val scope: AnalyticsScope = AnalyticsScope.All,
    val excludedNotebookIds: Set<Long> = emptySet(),
    val totalEntries: Int = 0,
    val noteCount: Int = 0,
    val taskCount: Int = 0,
    val totalCompleted: Int = 0,
    val activeTasks: Int = 0,
    val completedLast7Days: Int = 0,
    val completedLast30Days: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val byDay: List<Pair<LocalDate, Int>> = emptyList(),
    val byWeek: List<Pair<LocalDate, Int>> = emptyList(),
    val byHour: IntArray = IntArray(24),
    val byColor: List<Pair<String, Int>> = emptyList(),
    val byNotebook: List<Pair<String?, Int>> = emptyList(),
    val bestDay: Pair<LocalDate, Int>? = null,
    val totalElapsedMillis: Long = 0,
    val averageElapsedMillis: Long = 0,
    val fastestTitle: String? = null,
    val fastestMillis: Long = 0,
    val slowestTitle: String? = null,
    val slowestMillis: Long = 0,
    val recentCompleted: List<AnalyticsEntry> = emptyList(),
    val period: AnalyticsPeriod = AnalyticsPeriod.WEEK,
    val comparison: AnalyticsComparison = AnalyticsComparison(),
    val overdueTasks: Int = 0,
    val capturedAt: Long = 0,
    val nearestDeadline: Long? = null,
    val listRequest: AnalyticsListRequest? = null,
    val taskPage: AnalyticsTaskPage? = null,
    val nextClockBoundary: Long? = null
)

/** Pure calculations. A missing/deleted notebook is unfiled; protected metadata is always excluded. */
object AnalyticsSummary {
    fun build(entries: List<AnalyticsEntry>, notebooks: List<AnalyticsNotebook>, requested: AnalyticsScope,
        today: LocalDate, zone: ZoneId,
        now: Long = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
        period: AnalyticsPeriod = AnalyticsPeriod.WEEK, request: AnalyticsListRequest? = null): AnalyticsState {
        val excluded = notebooks.filter { it.isProtected || it.isDiary }.mapTo(hashSetOf()) { it.id }
        val available = notebooks.filter { !it.isDeleted && !it.isProtected && !it.isDiary }.associateBy { it.id }
        val scope = if (requested is AnalyticsScope.Notebook && requested.id !in available) AnalyticsScope.All else requested
        val scoped = entries.asSequence().filter { !it.isDeleted && !it.isPurged && !it.isProtected && it.notebookId !in excluded }
            .map { if (it.notebookId != null && it.notebookId !in available) it.copy(notebookId = null) else it }
            .filter { when (scope) { AnalyticsScope.All -> true; AnalyticsScope.Unfiled -> it.notebookId == null; is AnalyticsScope.Notebook -> it.notebookId == scope.id } }.toList()
        val tasks = scoped.filter { it.isTask }
        val completed = tasks.filter { it.isCompleted }
        val points = completed.map { TaskStatPoint(it.id, it.title, it.taskCompletedAt ?: it.updatedAt,
            it.taskElapsedMillis, taskColorOption(it.taskColor).key, it.notebookId) }
        val visiblePoints = points.filter { it.completedAt <= now }
        val windows = AnalyticsWindows.at(now, zone, period)
        val page = request?.let { AnalyticsPeriods.page(tasks, it, windows) }
        val byDay = AnalyticsMath.byDay(visiblePoints, 14, today, zone)
        val fastest = AnalyticsMath.fastest(points); val slowest = AnalyticsMath.slowest(points)
        return AnalyticsState(loading = false, scope = scope, excludedNotebookIds = excluded,
            totalEntries = scoped.size, noteCount = scoped.size - tasks.size, taskCount = tasks.size,
            totalCompleted = completed.size, activeTasks = tasks.size - completed.size,
            completedLast7Days = byDay.takeLast(7).sumOf { it.second },
            completedLast30Days = visiblePoints.count { AnalyticsMath.dayOf(it.completedAt, zone) in today.minusDays(29)..today },
            currentStreak = AnalyticsMath.currentStreak(points, today, zone), bestStreak = AnalyticsMath.bestStreak(points, zone),
            byDay = byDay, byWeek = AnalyticsMath.byWeek(points, 8, today, zone), byHour = AnalyticsMath.byHour(points, zone),
            byColor = AnalyticsMath.byColor(points), byNotebook = AnalyticsMath.byNotebook(points).map { (id, count) -> available[id]?.name to count },
            bestDay = AnalyticsMath.bestDay(points, zone), totalElapsedMillis = AnalyticsMath.totalElapsed(points),
            averageElapsedMillis = AnalyticsMath.averageElapsed(points), fastestTitle = fastest?.title, fastestMillis = fastest?.elapsedMillis ?: 0,
            slowestTitle = slowest?.title, slowestMillis = slowest?.elapsedMillis ?: 0, recentCompleted = recent(completed),
            period = period, comparison = AnalyticsPeriods.comparison(tasks, windows),
            overdueTasks = tasks.count { AnalyticsPeriods.overdue(it, now) }, capturedAt = now,
            nearestDeadline = tasks.asSequence().filter { it.isTask && !it.isCompleted }
                .mapNotNull { it.taskDueAt }.filter { com.notessync.app.ui.tasks.TaskDeadlines.valid(it) && it >= now }.minOrNull(),
            listRequest = page?.request, taskPage = page,
            nextClockBoundary = listOfNotNull(
                tasks.asSequence().filter { !it.isCompleted }.mapNotNull { it.taskDueAt }
                    .filter { com.notessync.app.ui.tasks.TaskDeadlines.valid(it) && it >= now }.minOrNull(),
                completed.asSequence().map { it.taskCompletedAt ?: it.updatedAt }.filter { it > now && it < Long.MAX_VALUE }.minOrNull()
            ).minOrNull())
    }

    private fun recent(entries: List<AnalyticsEntry>): List<AnalyticsEntry> {
        val order = compareBy<AnalyticsEntry> { it.taskCompletedAt ?: it.updatedAt }.thenBy { it.id }
        val top = PriorityQueue(20, order)
        entries.forEach { entry ->
            if (top.size < 20) top.add(entry)
            else if (order.compare(entry, top.peek()) > 0) { top.poll(); top.add(entry) }
        }
        return top.toList().sortedWith(order.reversed())
    }

    /** Do not render cached statistics while protection or the requested filter has changed. */
    fun visible(state: AnalyticsState, scope: AnalyticsScope, notebooks: List<AnalyticsNotebook>?,
        period: AnalyticsPeriod = state.period, request: AnalyticsListRequest? = state.listRequest): AnalyticsState {
        val excluded = notebooks?.filter { it.isProtected || it.isDiary }?.mapTo(hashSetOf()) { it.id }
        return if (excluded == null || state.scope != scope || state.excludedNotebookIds != excluded || state.period != period || state.listRequest != request ||
            (scope is AnalyticsScope.Notebook && notebooks.orEmpty().none { it.id == scope.id && !it.isProtected && !it.isDiary && !it.isDeleted })) AnalyticsState(scope = scope) else state
    }
}
