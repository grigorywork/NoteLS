package com.notessync.app.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Этап 30: обратный отсчёт — остаток, пауза, финиш, запуск. */
class TaskTimerCountdownTest {

    @Test
    fun `на паузе остаток равен заданной длительности`() {
        val remaining = TaskTimer.countdownRemaining(10 * 60_000L, null, now = 1_000_000L)
        assertEquals(600_000L, remaining)
    }

    @Test
    fun `в работе остаток убывает до дедлайна`() {
        val endAt = 1_000_000L + 600_000L
        assertEquals(500_000L, TaskTimer.countdownRemaining(600_000L, endAt, 1_100_000L))
        assertEquals(0L, TaskTimer.countdownRemaining(600_000L, endAt, 1_600_000L))
    }

    @Test
    fun `остаток не уходит в минус после дедлайна`() {
        val endAt = 1_600_000L
        assertEquals(0L, TaskTimer.countdownRemaining(600_000L, endAt, 9_999_999L))
    }

    @Test
    fun `финиш только когда таймер задан и дошёл до нуля`() {
        assertFalse(TaskTimer.countdownFinished(0L, null, 1_000L))
        assertFalse(TaskTimer.countdownFinished(600_000L, null, 1_000L))
        assertFalse(TaskTimer.countdownFinished(600_000L, 2_000L, 1_500L))
        assertTrue(TaskTimer.countdownFinished(600_000L, 2_000L, 2_000L))
        assertTrue(TaskTimer.countdownFinished(600_000L, 2_000L, 3_000L))
    }

    @Test
    fun `запуск возвращает дедлайн от текущего остатка`() {
        assertEquals(1_600_000L, TaskTimer.countdownStart(600_000L, 1_000_000L))
        assertNull(TaskTimer.countdownStart(0L, 1_000_000L))
        assertNull(TaskTimer.countdownStart(-5L, 1_000_000L))
    }

    @Test
    fun `пауза и продолжение сохраняют остаток`() {
        val now = 1_000_000L
        val endAt = TaskTimer.countdownStart(15 * 60_000L, now)!!
        // Пауза через 5 минут: остаток 10 минут.
        val pausedRemaining = TaskTimer.countdownRemaining(15 * 60_000L, endAt, now + 5 * 60_000L)
        assertEquals(10 * 60_000L, pausedRemaining)
        // Продолжение с остатка: новый дедлайн через 10 минут от момента резюма.
        val resumedEnd = TaskTimer.countdownStart(pausedRemaining, now + 7 * 60_000L)!!
        assertEquals(now + 17 * 60_000L, resumedEnd)
        assertEquals(10 * 60_000L, TaskTimer.countdownRemaining(pausedRemaining, resumedEnd, now + 7 * 60_000L))
    }

    @Test
    fun `формат нуля и больших значений`() {
        assertEquals("00:00:00", TaskTimer.format(0L))
        assertEquals("02:00:00", TaskTimer.format(2 * 60 * 60_000L))
        assertEquals("00:10:00", TaskTimer.format(10 * 60_000L))
    }
}
