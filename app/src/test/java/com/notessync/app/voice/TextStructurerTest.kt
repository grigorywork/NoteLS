package com.notessync.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Этап 32: структуризация распознанной речи. */
class TextStructurerTest {

    @Test
    fun `normalize убирает лишние пробелы`() {
        assertEquals("привет, как дела?", TextStructurer.normalize("  привет ,  как   дела ? "))
    }

    @Test
    fun `предложения разбиваются по пунктуации`() {
        val result = TextStructurer.sentences("Купить молоко. Помыть пол! Позвонить маме?")
        assertEquals(listOf("Купить молоко", "Помыть пол", "Позвонить маме"),
            result.map { it.trim('.', '!', '?') })
        assertEquals(3, result.size)
    }

    @Test
    fun `речевые маркеры разбивают поток на пункты`() {
        val result = TextStructurer.toPoints(
            "первое купить молоко потом помыть пол также позвонить маме"
        )
        assertEquals("- Купить молоко\n- Помыть пол\n- Позвонить маме", result)
    }

    @Test
    fun `во-первых во-вторых тоже маркеры`() {
        val result = TextStructurer.toPoints("во-первых проснуться во-вторых собраться на работу")
        assertEquals("- Проснуться\n- Собраться на работу", result)
    }

    @Test
    fun `текст без маркеров становится одним пунктом`() {
        assertEquals("- Купить молоко и хлеб", TextStructurer.toPoints("купить молоко и хлеб"))
    }

    @Test
    fun `пустой ввод даёт пустую строку`() {
        assertEquals("", TextStructurer.toPoints("   "))
        assertEquals("", TextStructurer.mainIdea(""))
        assertEquals(emptyList<String>(), TextStructurer.sentences(" "))
    }

    @Test
    fun `основная мысль из одного предложения`() {
        assertEquals("Купить молоко", TextStructurer.mainIdea("купить молоко"))
    }

    @Test
    fun `основная мысль выбирает предложение с ключевыми словами`() {
        val result = TextStructurer.mainIdea(
            "купить молоко и хлеб. погода сегодня хорошая. молоко и хлеб самые важные продукты"
        )
        assertTrue(result.startsWith("Молоко и хлеб самые важные продукты"))
        assertTrue(result.contains("Ключевое:"))
        assertTrue(result.contains("молоко"))
    }

    @Test
    fun `стоп-слова не попадают в ключевые`() {
        val result = TextStructurer.mainIdea("и в на с по. купить молоко. молоко свежее вкусное")
        assertTrue(!result.contains("Ключевое: и,"))
    }
}
