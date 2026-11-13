package com.notessync.app.data.local.model

/** Calendar widgets never need note bodies, formatting or attachment paths. */
data class WidgetDaySnippet(val id: Long, val dayKey: Long, val title: String,
    val isTask: Boolean, val isCompleted: Boolean, val taskColor: String)
data class WidgetDayCount(val dayKey: Long, val total: Long)
