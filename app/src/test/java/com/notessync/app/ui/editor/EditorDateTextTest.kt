package com.notessync.app.ui.editor

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test
import org.junit.Assert.*

class EditorDateTextTest {
    private val timestamp = Instant.parse("2026-11-28T21:30:00Z").toEpochMilli()
    @Test fun `unsaved and invalid dates have no invented time`() {
        for (value in listOf(null, 0L, -1L)) assertEquals("—", editorDateText(value, Locale.US, ZoneId.of("UTC")))
    }
    @Test fun `date follows timezone across midnight and uses application locale`() {
        val ru = editorDateText(timestamp, Locale("ru", "RU"), ZoneId.of("Europe/Moscow"))
        val en = editorDateText(timestamp, Locale.US, ZoneId.of("UTC"))
        assertTrue(ru, ru.contains("29.11.2026") || ru.contains("29.11.26"))
        assertTrue(en, en.contains("11/28/26")); assertNotEquals(ru, en)
    }
    @Test fun `extreme committed millisecond timestamps format without overflow`() {
        assertTrue(editorDateText(Long.MAX_VALUE, Locale.US, ZoneId.of("UTC")).isNotBlank())
    }
}
