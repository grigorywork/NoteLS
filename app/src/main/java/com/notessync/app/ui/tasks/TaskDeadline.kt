package com.notessync.app.ui.tasks

import com.notessync.app.i18n.uiText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TaskDueFilter(private val text: String) {
    ALL("Любой срок"), OVERDUE("Просроченные"), TODAY("Срок сегодня"), UPCOMING("Предстоящие"), NONE("Без срока");
    val label get() = uiText(text)
}
data class TaskFilters(val due: TaskDueFilter = TaskDueFilter.ALL, val priority: Int? = null,
    val notebookId: Long? = null, val tagId: Long? = null) {
    fun normalized() = copy(priority = priority?.takeIf { it in 0..3 }, notebookId = notebookId?.takeIf { it >= 0 }, tagId = tagId?.takeIf { it > 0 })
}
object TaskDeadlines {
    // Picker years1900..2100 with a margin for every supported UTC offset.
    const val MIN = -2209075200000L
    const val MAX = 4134067200000L
    fun valid(value: Long?) = value == null || value in MIN..MAX
    fun dayBounds(now: Long, zone: ZoneId): Pair<Long, Long> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    fun nextRefresh(now: Long, nearest: Long?, zone: ZoneId, capturedAt: Long = now): Long {
        if (nearest != null && nearest >= capturedAt && nearest < now) return now + 1
        val midnight = dayBounds(now, zone).second
        return minOf(midnight, nearest?.takeIf { it >= now && it < Long.MAX_VALUE }?.plus(1) ?: midnight)
    }
}

/** Recheck cached rows at a clock boundary while the new SQL result is in flight. */
internal fun <T : com.notessync.app.data.local.model.NoteListEntry> filterCachedDeadlines(notes: List<T>, filter: TaskDueFilter,
    now: Long, zone: ZoneId): List<T> {
    if (filter == TaskDueFilter.ALL) return notes
    val bounds = if (filter == TaskDueFilter.TODAY) TaskDeadlines.dayBounds(now, zone) else 0L to 0L
    return notes.filter { note ->
        val at = note.taskDueAt
        when (filter) {
            TaskDueFilter.ALL -> true
            TaskDueFilter.NONE -> at == null
            TaskDueFilter.OVERDUE -> at != null && at < now
            TaskDueFilter.UPCOMING -> at != null && at >= now
            TaskDueFilter.TODAY -> at != null && at >= bounds.first && at < bounds.second
        }
    }
}
