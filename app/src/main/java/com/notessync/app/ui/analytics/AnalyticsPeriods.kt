package com.notessync.app.ui.analytics

import com.notessync.app.data.local.model.AnalyticsEntry
import com.notessync.app.ui.tasks.TaskDeadlines
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AnalyticsPeriod(val days: Int) { WEEK(7), MONTH(30) }
enum class AnalyticsTaskList { CURRENT, PREVIOUS, ACTIVE, OVERDUE }
data class AnalyticsListRequest(val kind: AnalyticsTaskList, val page: Int = 0)
data class AnalyticsTaskRow(val id: Long, val syncId: String, val title: String, val completedAt: Long?, val dueAt: Long?)
data class AnalyticsTaskPage(val request: AnalyticsListRequest, val total: Int, val pageCount: Int, val rows: List<AnalyticsTaskRow>)
data class AnalyticsComparison(val current: Int = 0, val previous: Int = 0) { val difference: Int get() = current - previous }

/** Local dates define disjoint windows; the current window ends at the captured instant, inclusive. */
internal data class AnalyticsWindows(val previousStart: Long, val currentStart: Long, val now: Long) {
    fun current(at: Long) = at >= currentStart && at <= now
    fun previous(at: Long) = at >= previousStart && at < currentStart && at <= now
    companion object {
        fun at(now: Long, zone: ZoneId, period: AnalyticsPeriod): AnalyticsWindows {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val first = today.minusDays(period.days.toLong() - 1)
            return AnalyticsWindows(first.minusDays(period.days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
                first.atStartOfDay(zone).toInstant().toEpochMilli(), now)
        }
    }
}

/** Inputs are already privacy/scope filtered by AnalyticsSummary; no note body is accepted here. */
internal object AnalyticsPeriods {
    const val PAGE_SIZE = 20
    fun comparison(tasks: List<AnalyticsEntry>, windows: AnalyticsWindows) = AnalyticsComparison(
        tasks.count { it.isTask && it.isCompleted && windows.current(it.taskCompletedAt ?: it.updatedAt) },
        tasks.count { it.isTask && it.isCompleted && windows.previous(it.taskCompletedAt ?: it.updatedAt) })
    fun overdue(task: AnalyticsEntry, now: Long): Boolean = task.isTask && !task.isCompleted &&
        TaskDeadlines.valid(task.taskDueAt) && task.taskDueAt?.let { it < now } == true
    fun page(tasks: List<AnalyticsEntry>, request: AnalyticsListRequest, windows: AnalyticsWindows): AnalyticsTaskPage {
        val matching = tasks.filter { task -> task.isTask && when (request.kind) {
            AnalyticsTaskList.CURRENT -> task.isCompleted && windows.current(task.taskCompletedAt ?: task.updatedAt)
            AnalyticsTaskList.PREVIOUS -> task.isCompleted && windows.previous(task.taskCompletedAt ?: task.updatedAt)
            AnalyticsTaskList.ACTIVE -> !task.isCompleted
            AnalyticsTaskList.OVERDUE -> overdue(task, windows.now)
        } }
        val count = matching.size
        val pages = maxOf(1, ((count.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt())
        val page = request.page.coerceIn(0, pages - 1)
        val ordered = when (request.kind) {
            AnalyticsTaskList.CURRENT, AnalyticsTaskList.PREVIOUS -> matching.sortedWith(
                compareByDescending<AnalyticsEntry> { it.taskCompletedAt ?: it.updatedAt }.thenByDescending { it.id })
            AnalyticsTaskList.OVERDUE -> matching.sortedWith(compareBy<AnalyticsEntry> { it.taskDueAt }.thenBy { it.id })
            AnalyticsTaskList.ACTIVE -> matching.sortedWith(compareByDescending<AnalyticsEntry> { it.updatedAt }.thenByDescending { it.id })
        }
        return AnalyticsTaskPage(request.copy(page = page), count, pages,
            ordered.drop(page * PAGE_SIZE).take(PAGE_SIZE).map {
                AnalyticsTaskRow(it.id, it.syncId, it.title, if (it.isCompleted) it.taskCompletedAt ?: it.updatedAt else null, it.taskDueAt)
            })
    }
}
