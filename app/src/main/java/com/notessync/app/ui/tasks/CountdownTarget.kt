package com.notessync.app.ui.tasks

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Nearest future local time. Handles DST gaps and both instants of an overlap. */
object CountdownTarget {
    fun next(now: Instant, hour: Int, minute: Int, zone: ZoneId): Instant {
        val time = LocalTime.of(hour, minute)
        val today = now.atZone(zone).toLocalDate()
        return (0L..2L).asSequence().flatMap { days ->
            val local = today.plusDays(days).atTime(time)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.isEmpty()) sequenceOf(local.atZone(zone).toInstant())
            else offsets.asSequence().map { local.toInstant(it) }.sorted()
        }.first { it > now }
    }
}
