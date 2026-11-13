package com.notessync.app.widget

import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.model.WidgetDaySnippet

data class WidgetDaySnapshot(val entries: List<WidgetDaySnippet>, val total: Long) {
    fun previews() = entries.map { WidgetDayEntry(it.title, it.isTask, it.isCompleted, it.taskColor) }
}

/** At most 42 count scalars + 126 short titles, even for a densely populated month. */
internal suspend fun loadWidgetDays(db: AppDatabase, first: Long, last: Long): Map<Long, WidgetDaySnapshot> {
    require(last >= first && last - first in 0..41)
    return db.withTransaction {
        db.noteDao().getWidgetDayCounts(first, last).associate { count ->
            count.dayKey to WidgetDaySnapshot(db.noteDao().getWidgetDaySnippets(count.dayKey), count.total)
        }
    }
}
