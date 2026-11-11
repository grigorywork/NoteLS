package com.notessync.app.reminders

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderTimeTest {
    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:00"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun pickerUtcDateUsesLocalSelectedTime() {
        val pickerDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 12, 0, 0, 0)
        }.timeInMillis

        val result = ReminderTime.combineDateAndTime(pickerDate, 18, 45)
        val local = Calendar.getInstance().apply { timeInMillis = result }

        assertEquals(2026, local.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, local.get(Calendar.MONTH))
        assertEquals(12, local.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, local.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, local.get(Calendar.MINUTE))
    }

    @Test
    fun tomorrowMorningIsAtNine() {
        val now = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 10, 23, 30, 0)
        }.timeInMillis

        val result = Calendar.getInstance().apply {
            timeInMillis = ReminderTime.tomorrowMorning(now)
        }

        assertEquals(11, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }

    @Test
    fun hourPresetsAddExactDuration() {
        val now = 1_763_000_000_000L
        listOf(1, 2, 6, 8, 12).forEach { hours ->
            assertEquals(now + hours * 3_600_000L, ReminderTime.afterHours(hours, now))
        }
    }

    @Test
    fun dayAfterTomorrowMorningIsAtNine() {
        val now = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 30, 23, 30, 0)
        }.timeInMillis

        val result = Calendar.getInstance().apply {
            timeInMillis = ReminderTime.dayAfterTomorrowMorning(now)
        }

        assertEquals(Calendar.DECEMBER, result.get(Calendar.MONTH))
        assertEquals(2, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }

    @Test
    fun dayDropDownSupportsSelectedMorning() {
        val now = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 10, 18, 20, 0)
        }.timeInMillis

        val result = Calendar.getInstance().apply {
            timeInMillis = ReminderTime.morningAfterDays(7, now)
        }

        assertEquals(17, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }
}
