package com.notessync.app.widget

import com.notessync.app.i18n.UiCatalog
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.i18n.UiStrings

data class WidgetDayEntry(
    val title: String,
    val isTask: Boolean,
    val isCompleted: Boolean,
    val taskColor: String = "violet"
)

/** Одна строка предпросмотра: текст и цвет задачи (null — обычный текст заметки). */
data class WidgetPreviewLine(
    val text: String,
    val colorKey: String?,
    val isCompleted: Boolean = false
)

object WidgetDayPreview {
    const val MAX_ENTRIES = 3

    /**
     * Этап 33: строки с цветовой разметкой. Задача получает свой taskColor,
     * чтобы задачи разных цветов не сливались; заметки остаются нейтральными.
     */
    fun lines(entries: List<WidgetDayEntry>, total: Long = entries.size.toLong(), language: UiLanguage = UiStrings.language): List<WidgetPreviewLine> {
        if (entries.isEmpty()) return emptyList()
        val visible = entries.take(MAX_ENTRIES).map { entry ->
            val prefix = when {
                entry.isTask && entry.isCompleted -> "☑ "
                entry.isTask -> "□ "
                else -> ""
            }
            val fallback = if (entry.isTask) UiCatalog.translate(language, "Задача") else UiCatalog.translate(language, "Заметка")
            val title = entry.title.ifBlank { fallback }
                .lineSequence()
                .first()
                .trim()
                .ifBlank { fallback }
            WidgetPreviewLine(
                text = prefix + title,
                colorKey = if (entry.isTask) entry.taskColor else null,
                isCompleted = entry.isTask && entry.isCompleted
            )
        }
        val remaining = (total - visible.size).coerceAtLeast(0)
        return visible + listOfNotNull(
            if (remaining > 0) WidgetPreviewLine("+$remaining", null) else null
        )
    }

    fun format(entries: List<WidgetDayEntry>): String =
        lines(entries).joinToString("\n") { it.text }
}
