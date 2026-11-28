package com.notessync.app.ui.tasks

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TaskDurationTest {
    private val style = TaskDurationStyle()
    @Test fun `hour and day transitions retain full precision and compact minutes`() {
        assertEquals("00:00", style.format(0))
        assertEquals("00:59", style.format(59_999))
        assertEquals("59:59", style.format(3_599_999))
        assertEquals("1ч 00м", style.format(3_600_000))
        assertEquals("1ч00м", style.format(3_600_000, compact = true))
        assertEquals("1ч 02м 03с", style.format(3_723_000))
        assertEquals("1ч02м", style.format(3_723_000, compact = true))
        assertEquals("23ч 59м 59с", style.format(86_399_999))
        assertEquals("1д 00ч 00м", style.format(86_400_000))
        assertEquals("1д00ч00м", style.format(86_400_000, compact = true))
        assertEquals("2д 01ч 02м 03с", style.format(176_523_000))
    }
    @Test fun `disabled conversion exposes total minutes without changing duration`() {
        val minutes = TaskDurationStyle(automaticUnits = false)
        assertEquals("60:00", minutes.format(3_600_000))
        assertEquals("1440:00", minutes.format(86_400_000, compact = true))
        assertEquals("2942:03", minutes.format(176_523_000))
        assertEquals("00:01", minutes.format(1_999))
    }
    @Test fun `negative values clamp and maximum long duration does not overflow`() {
        assertEquals("00:00", style.format(Long.MIN_VALUE))
        assertEquals("00:00", style.format(-1))
        assertEquals("106751991167д 07ч 12м 55с", style.format(Long.MAX_VALUE))
        assertEquals("153722867280912:55", TaskDurationStyle(false).format(Long.MAX_VALUE))
    }
    @Test fun `UI language is explicit and formatting never changes the device locale`() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale("ru", "RU"), Locale("ar", "EG"), Locale("tr", "TR"))) {
                Locale.setDefault(locale)
                assertEquals("1d 03h 00m 05s", TaskDurationStyle(english = true).format(97_205_000))
                assertEquals("1d03h00m", TaskDurationStyle(english = true).format(97_205_000, true))
                assertEquals("1д 03ч 00м 05с", style.format(97_205_000))
                assertEquals(locale, Locale.getDefault())
            }
        } finally { Locale.setDefault(original) }
    }
}
