package com.notessync.app.ui.tasks

data class TaskTimerSnapshot(
    val elapsedMillis: Long,
    val startedAt: Long?
)

object TaskTimer {
    fun elapsedAt(elapsedMillis: Long, startedAt: Long?, now: Long): Long {
        val runningDelta = if (startedAt == null) 0L else (now - startedAt).coerceAtLeast(0L)
        return (elapsedMillis.coerceAtLeast(0L) + runningDelta).coerceAtLeast(0L)
    }

    fun toggle(elapsedMillis: Long, startedAt: Long?, now: Long): TaskTimerSnapshot =
        if (startedAt == null) {
            TaskTimerSnapshot(elapsedMillis.coerceAtLeast(0L), now)
        } else {
            TaskTimerSnapshot(elapsedAt(elapsedMillis, startedAt, now), null)
        }

    fun pause(elapsedMillis: Long, startedAt: Long?, now: Long): TaskTimerSnapshot =
        if (startedAt == null) {
            TaskTimerSnapshot(elapsedMillis.coerceAtLeast(0L), null)
        } else {
            TaskTimerSnapshot(elapsedAt(elapsedMillis, startedAt, now), null)
        }

    fun reset(): TaskTimerSnapshot = TaskTimerSnapshot(0L, null)

    // ---------- Обратный отсчёт (Этап 30) ----------

    /** Остаток таймера: на паузе — сам [countdownMillis], в работе — до [endAt]. */
    fun countdownRemaining(countdownMillis: Long, endAt: Long?, now: Long): Long =
        if (endAt == null) countdownMillis.coerceAtLeast(0L)
        else (endAt - now).coerceAtLeast(0L)

    /** Таймер дошёл до нуля (только если был задан). */
    fun countdownFinished(countdownMillis: Long, endAt: Long?, now: Long): Boolean =
        countdownMillis > 0L && countdownRemaining(countdownMillis, endAt, now) == 0L

    /** Запуск/возобновление: вернуть дедлайн от текущего остатка. */
    fun countdownStart(countdownMillis: Long, now: Long): Long? =
        if (countdownMillis <= 0L) null else now + countdownMillis

    fun format(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return buildString(20) {
            if (hours < 10) append('0')
            append(hours); append(':')
            if (minutes < 10) append('0')
            append(minutes); append(':')
            if (seconds < 10) append('0')
            append(seconds)
        }
    }
}
