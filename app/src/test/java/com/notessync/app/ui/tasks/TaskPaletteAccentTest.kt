package com.notessync.app.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Этап 35: у каждого цвета задачи есть видимый насыщенный акцент. */
class TaskPaletteAccentTest {

    @Test
    fun `у всех вариантов есть акцент отличный от пастельного фона`() {
        taskColorOptions.forEach { option ->
            assertNotEquals(
                "У ${option.key} акцент совпадает с фоном — цвет не будет виден",
                option.completedContainer, option.accent
            )
        }
    }

    @Test
    fun `акценты уникальны`() {
        val accents = taskColorOptions.map { it.accent }
        assertEquals(accents.size, accents.toSet().size)
    }

    @Test
    fun `неизвестный ключ откатывается на фиолетовый`() {
        assertEquals("violet", taskColorOption("нет-такого").key)
        assertEquals(taskColorOptions.first().accent, taskColorOption("").accent)
    }

    @Test
    fun `ключи по-прежнему полные`() {
        assertEquals(
            setOf("violet", "blue", "mint", "yellow", "orange", "pink"),
            taskColorOptions.map { it.key }.toSet()
        )
        assertTrue(taskColorOptions.size >= 6)
    }
}
