package com.notessync.app.reminders

import java.util.Calendar
import java.util.TimeZone

object ReminderTime {
    fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long {
        val pickerDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateMillis
        }
        return Calendar.getInstance().apply {
            clear()
            set(
                pickerDate.get(Calendar.YEAR),
                pickerDate.get(Calendar.MONTH),
                pickerDate.get(Calendar.DAY_OF_MONTH),
                hour,
                minute,
                0
            )
        }.timeInMillis
    }

    fun afterHours(hours: Int, now: Long = System.currentTimeMillis()): Long {
        require(hours > 0)
        return now + hours * 3_600_000L
    }

    fun morningAfterDays(days: Int, now: Long = System.currentTimeMillis()): Long {
        require(days > 0)
        return Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun tomorrowMorning(now: Long = System.currentTimeMillis()): Long =
        morningAfterDays(1, now)

    fun dayAfterTomorrowMorning(now: Long = System.currentTimeMillis()): Long =
        morningAfterDays(2, now)
}
