package com.notessync.app.ui.tasks

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CountdownTargetTest {
    @Test fun `nineteen today or tomorrow always starts from now`() {
        val zone = ZoneId.of("Europe/Moscow")
        val before = ZonedDateTime.of(2026, 9, 13, 18, 20, 30, 0, zone).toInstant()
        val target = CountdownTarget.next(before, 19, 0, zone)
        assertEquals(Duration.ofMinutes(39).plusSeconds(30), Duration.between(before, target))
        assertEquals(13, target.atZone(zone).dayOfMonth)
        assertEquals(14, CountdownTarget.next(target, 19, 0, zone).atZone(zone).dayOfMonth)
        val after = target.plusSeconds(10)
        assertEquals(Duration.ofHours(24).minusSeconds(10), Duration.between(after, CountdownTarget.next(after, 19, 0, zone)))
    }
    @Test fun `DST gap shows actual shifted time and overlap chooses remaining second instant`() {
        val zone = ZoneId.of("Europe/Berlin")
        val gap = CountdownTarget.next(Instant.parse("2026-03-29T00:00:00Z"), 2, 30, zone)
        assertEquals(LocalTime.of(3, 30), gap.atZone(zone).toLocalTime())
        val first = CountdownTarget.next(Instant.parse("2026-10-25T00:00:00Z"), 2, 30, zone)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), CountdownTarget.next(first.plusSeconds(1), 2, 30, zone))
    }
    @Test fun `invalid local time is rejected`() {
        assertTrue(runCatching { CountdownTarget.next(Instant.EPOCH, 24, 0, ZoneOffset.UTC) }.isFailure)
    }
}
