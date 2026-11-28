package com.notessync.app.tasks.running

/** Scalar projection: never loads document text, formatting, attachments or password material. */
data class RunningTaskEntry(
    val id: Long, val syncId: String, val title: String, val hidden: Boolean,
    val taskElapsedMillis: Long, val taskTimerStartedAt: Long?,
    val taskCountdownMillis: Long, val taskCountdownEndAt: Long?
)

internal enum class RunningClock { STOPWATCH, COUNTDOWN }
internal enum class RunningAction { PAUSE, COMPLETE }
internal data class RunningControl(val id: Long, val syncId: String, val clock: RunningClock,
    val expectedAt: Long, val action: RunningAction)

internal fun RunningTaskEntry.matches(control: RunningControl): Boolean =
    !hidden && id == control.id && syncId == control.syncId && control.expectedAt > 0 &&
        (if (control.clock == RunningClock.STOPWATCH) taskTimerStartedAt else taskCountdownEndAt) == control.expectedAt

/** System chronometers are used for ordinary intervals; corrupt/extreme dates remain bounded static text. */
internal object RunningClockTime {
    const val MAX_NATIVE_MILLIS = 10L * 366 * 24 * 60 * 60 * 1000
    fun elapsed(stored: Long, start: Long?, now: Long): Long {
        val base = stored.coerceAtLeast(0)
        val delta = if (start == null || start >= now) 0 else if (start < 0 && now > Long.MAX_VALUE + start) Long.MAX_VALUE else now - start
        return if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta
    }
    fun remaining(end: Long, now: Long): Long =
        if (end <= now) 0 else if (now < 0 && end > Long.MAX_VALUE + now) Long.MAX_VALUE else end - now
}
