package com.notessync.app.data.local.model

/** At most three bounded public rows; no attachment bytes or document formatting. */
data class CompactWidgetEntry(val id: Long, val syncId: String, val title: String, val content: String,
    val isTask: Boolean, val isCompleted: Boolean, val isPinned: Boolean, val taskColor: String, val noteColor: String)
