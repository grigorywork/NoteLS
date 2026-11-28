package com.notessync.app.ui.tasks

/** Display only: durations are absolute 24-hour days, not calendar dates or stored timer state. */
data class TaskDurationStyle(val automaticUnits: Boolean = true, val english: Boolean = false) {
    fun format(millis: Long, compact: Boolean = false): String {
        val seconds = millis.coerceAtLeast(0L) / 1_000L
        val totalMinutes = seconds / 60L
        val remainderSeconds = seconds % 60L
        if (!automaticUnits || seconds < 3_600L) {
            return totalMinutes.toString().padStart(2, '0') + ":" + remainderSeconds.toString().padStart(2, '0')
        }
        val days = seconds / 86_400L
        val hours = seconds / 3_600L % 24L
        val minutes = totalMinutes % 60L
        val separator = if (compact) "" else " "
        return buildString(40) {
            if (days > 0) { append(days); append(if (english) 'd' else 'д'); append(separator) }
            append(if (days > 0) hours.toString().padStart(2, '0') else hours.toString())
            append(if (english) 'h' else 'ч'); append(separator)
            append(minutes.toString().padStart(2, '0')); append(if (english) 'm' else 'м')
            // Compact long timers keep minutes legible; the full accessibility label retains seconds.
            if (!compact && remainderSeconds > 0) {
                append(separator); append(remainderSeconds.toString().padStart(2, '0'))
                append(if (english) 's' else 'с')
            }
        }
    }
}
