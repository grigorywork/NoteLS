package com.notessync.app.ui.tasks

import java.util.Locale
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** Differential contract for removing Formatter allocations from each visible clock update. */
class TaskTimerFormatCompatibilityTest {
    @Test fun `direct clock text preserves former ROOT formatting across signed durations and locales`() {
        val random = Random(202611144L)
        val samples = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 999L, 1000L, 59000L, 60000L, 3599999L,
            3600000L, 86399999L, 360000000L, Long.MAX_VALUE) + List(3000) { random.nextLong() }
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("ru"), Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                for (value in samples) {
                    val totalSeconds = value.coerceAtLeast(0L) / 1000L
                    val reference = String.format(Locale.ROOT, "%02d:%02d:%02d", totalSeconds / 3600L,
                        (totalSeconds % 3600L) / 60L, totalSeconds % 60L)
                    assertEquals("Duration=$value locale=$locale", reference, TaskTimer.format(value))
                }
            }
        } finally { Locale.setDefault(original) }
    }
}
