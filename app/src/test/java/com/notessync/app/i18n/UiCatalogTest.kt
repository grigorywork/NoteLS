package com.notessync.app.i18n

import com.notessync.app.notes.photos.PhotoHandle
import com.notessync.app.settings.*
import com.notessync.app.ui.common.formatBytes
import com.notessync.app.ui.tasks.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

class UiCatalogTest {
    @After fun reset() { UiStrings.language = UiLanguage.RUSSIAN }
    @Test fun `all English messages preserve placeholder signatures and contain no Russian UI text`() {
        val placeholder = Regex("%(?:[0-9]+[$])?[-+0-9.]*[sdf]")
        assertTrue(UiCatalog.english.size >= 540)
        UiCatalog.english.forEach { (key, value) ->
            assertEquals(key, placeholder.findAll(key).map { it.value }.sorted().toList(), placeholder.findAll(value).map { it.value }.sorted().toList())
            assertFalse(value, value.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' })
            assertTrue(key, value.isNotBlank())
        }
    }
    @Test fun `argument formatting is single pass and never translates or reparses user content`() {
        val marker = "%2" + '$' + "s"
        val user = "Удалить $marker 😀 {текст} 100%"
        val key = "Открыть «%1" + '$' + "s»"
        assertEquals("Open “$user”", UiCatalog.translate(UiLanguage.ENGLISH, key, arrayOf(user)))
        assertEquals("Открыть «$user»", UiCatalog.translate(UiLanguage.RUSSIAN, key, arrayOf(user)))
        assertEquals("Select: $user", UiCatalog.translate(UiLanguage.ENGLISH, "Выбрать: ") + user)
    }
    @Test fun `Russian remains original and missing keys do not crash`() {
        assertEquals("Сохранить", UiCatalog.translate(UiLanguage.RUSSIAN, "Сохранить"))
        assertEquals("Новая неизвестная подпись", UiCatalog.translate(UiLanguage.ENGLISH, "Новая неизвестная подпись"))
        assertEquals("Redo", UiCatalog.translate(UiLanguage.ENGLISH, "Повторить", context = "history"))
        assertEquals("Retry", UiCatalog.translate(UiLanguage.ENGLISH, "Повторить"))
        assertEquals("Повторить", UiCatalog.translate(UiLanguage.RUSSIAN, "Повторить", context = "history"))
    }
    @Test fun `already initialized label objects follow language in both directions without changing ids`() {
        UiStrings.language = UiLanguage.RUSSIAN
        val color = taskColorOptions.first(); val font = AppFont.SYSTEM; val priority = TaskPriority.HIGH; val handle = PhotoHandle.TOP_LEFT
        UiStrings.language = UiLanguage.ENGLISH
        assertEquals("Violet", color.label); assertEquals("Default", font.label)
        assertEquals("High", priority.label); assertEquals("Top left corner", handle.label)
        assertEquals("Manual only", BackupFrequency.MANUAL.label)
        UiStrings.language = UiLanguage.RUSSIAN
        assertEquals("Фиолетовый", color.label); assertEquals("Стандартный", font.label)
        assertEquals("Высокий", priority.label); assertEquals("Левый верхний угол", handle.label)
        assertEquals("violet", color.key); assertEquals(3, priority.value); assertEquals("SYSTEM", font.name)
    }
    @Test fun `byte formatting follows UI language without changing the device locale`() {
        val before = Locale.getDefault()
        UiStrings.language = UiLanguage.ENGLISH; assertEquals("1.0 MB", formatBytes(1_048_576))
        UiStrings.language = UiLanguage.RUSSIAN; assertEquals("1,0 МБ", formatBytes(1_048_576))
        assertEquals(before, Locale.getDefault())
    }
    @Test fun `every static uiText key in production source has an English entry`() {
        val root = listOf(File("src/main/java/com/notessync/app"), File("app/src/main/java/com/notessync/app")).first { it.isDirectory }
        val prefix = "uiText(" + '"'
        var checked = 0
        root.walkTopDown().filter { it.extension == "kt" && it.parentFile?.name != "i18n" }.forEach { file ->
            val source = file.readText()
            var start = source.indexOf(prefix)
            while (start >= 0) {
                var cursor = start + prefix.length
                val key = buildString {
                    while (cursor < source.length && source[cursor] != '"') {
                        var ch = source[cursor++]
                        if (ch == '\\') {
                            ch = source[cursor++]
                            append(when (ch) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; else -> ch })
                        } else append(ch)
                    }
                }
                assertTrue("${file.name}: $key", UiCatalog.english.containsKey(key))
                checked++; start = source.indexOf(prefix, cursor + 1)
            }
        }
        assertTrue(checked.toString(), checked > 500)
    }
}
