package com.notessync.app.ui.common

import java.text.DateFormat
import java.util.Date

fun formatDateTime(timestamp: Long): String =
    if (timestamp == 0L) "Никогда"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576f)
    bytes >= 1024 -> "%.1f КБ".format(bytes / 1024f)
    else -> "$bytes Б"
}
