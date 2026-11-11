package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Операции панели форматирования редактора (Этап 3).
 * Все функции — чистые: принимают значение поля, возвращают новое.
 */
object MarkdownEdit {

    /** Оборачивает выделение маркером (** или *); повторный вызов снимает обёртку. */
    fun toggleWrap(value: TextFieldValue, marker: String): TextFieldValue {
        val sel = value.selection
        val start = sel.min.coerceIn(0, value.text.length)
        val end = sel.max.coerceIn(0, value.text.length)
        val text = value.text

        // Уже обёрнуто — снять обёртку.
        if (start - marker.length >= 0 && end + marker.length <= text.length &&
            text.substring(start - marker.length, start) == marker &&
            text.substring(end, end + marker.length) == marker
        ) {
            val newText = text.removeRange(end, end + marker.length).removeRange(start - marker.length, start)
            return value.copy(text = newText, selection = TextRange(start - marker.length, end - marker.length))
        }
        if (start == end) {
            // Режим ввода без выделения (этап 27): маркеры не показываются,
            // кнопка включает/выключает набор стилизованного текста.
            val before = start - marker.length
            val afterHasMarker = text.substring(start).startsWith(marker)
            val beforeHasMarker =
                before >= 0 && text.substring(before, start) == marker
            when {
                // Пустая пара маркеров вокруг курсора — убрать оба.
                beforeHasMarker && afterHasMarker -> {
                    val newText = text.removeRange(start, start + marker.length)
                        .removeRange(before, start)
                    return value.copy(text = newText, selection = TextRange(before))
                }
                // Курсор вплотную перед закрывающим маркером — выйти за него.
                afterHasMarker -> {
                    return value.copy(selection = TextRange(start + marker.length))
                }
                // Вход в режим: пара маркеров, курсор между ними.
                else -> {
                    val newText = text.substring(0, start) + marker + marker + text.substring(end)
                    return value.copy(text = newText, selection = TextRange(start + marker.length))
                }
            }
        }
        val newText = text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end)
        return value.copy(text = newText, selection = TextRange(start + marker.length, end + marker.length))
    }

    /**
     * true, если курсор находится внутри пары маркеров на текущей строке
     * (для подсветки активного режима жирный/курсив на кнопках).
     * Эвристика: нечётное число маркеров слева от курсора в пределах строки.
     */
    fun isInsideWrap(text: String, offset: Int, marker: String): Boolean {
        if (text.isEmpty() || marker.isEmpty()) return false
        val at = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
        val before = text.substring(lineStart, at)
        var count = 0
        var i = 0
        while (true) {
            val found = before.indexOf(marker, i)
            if (found < 0) break
            count++
            i = found + marker.length
        }
        return count % 2 == 1
    }

    /** Ставит/снимает префикс у всех строк выделения («# », «- », «- [ ] »). */
    fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)

        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val lines = block.split('\n')

        val allHave = lines.all { it.startsWith(prefix) }
        val newBlock = lines.joinToString("\n") { line ->
            when {
                allHave -> line.removePrefix(prefix)
                // Пустые строки пропускаем только внутри многострочного блока;
                // одиночная пустая строка получает префикс (новый пункт).
                line.isBlank() && lines.size > 1 -> line
                else -> prefix + line
            }
        }
        val newText = text.substring(0, lineStart) + newBlock + text.substring(lineEnd)
        val cursor = (lineStart + newBlock.length).coerceAtMost(newText.length)
        return value.copy(text = newText, selection = TextRange(cursor))
    }

    /** Вид списка для кнопок панели. */
    enum class ListKind(val prefix: String) {
        BULLET("- "),
        CHECKLIST("- [ ] ")
    }

    /**
     * Циклическое переключение списка (этап 25): строки без префикса получают
     * нужный; строки другого вида списка переводятся на нужный; если все строки
     * уже в нужном виде (отмеченный чекбокс считается чек-листом) — префикс
     * снимается. Префиксы никогда не накладываются друг на друга
     * («- - [ ] текст» исключено).
     */
    fun toggleListKind(value: TextFieldValue, kind: ListKind): TextFieldValue {
        val text = value.text
        val start = value.selection.min.coerceIn(0, text.length)
        val end = value.selection.max.coerceIn(0, text.length)

        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val lines = block.split('\n')

        // Вид и длина маркера строки; null — строка не пункт списка.
        fun markerOf(line: String): Pair<ListKind, Int>? = when {
            line.startsWith("- [ ] ") || line.startsWith("- [x] ") ||
                line.startsWith("- [X] ") -> ListKind.CHECKLIST to 6
            line.startsWith("- ") -> ListKind.BULLET to 2
            else -> null
        }

        val allTarget = lines.any { markerOf(it)?.first == kind } &&
            lines.all { markerOf(it)?.first == kind || (it.isBlank() && lines.size > 1) }
        val newBlock = lines.joinToString("\n") { line ->
            val marker = markerOf(line)
            when {
                allTarget -> if (marker != null) line.substring(marker.second) else line
                line.isBlank() && lines.size > 1 -> line
                else -> kind.prefix + (marker?.let { line.substring(it.second) } ?: line)
            }
        }
        val newText = text.substring(0, lineStart) + newBlock + text.substring(lineEnd)
        val cursor = (lineStart + newBlock.length).coerceAtMost(newText.length)
        return value.copy(text = newText, selection = TextRange(cursor))
    }

    /**
     * Автопродолжение списков по Enter (этап 25). Вызывается при каждом вводе:
     * если единственное изменение — вставленный перевод строки в позиции курсора,
     * и строка была пунктом списка/чек-листа, новый пункт создаётся автоматически.
     * Enter на пустом пункте снимает маркер (выход из списка), лишняя пустая
     * строка не создаётся. Во всех остальных случаях ввод возвращается как есть.
     */
    fun handleEnter(prev: TextFieldValue, next: TextFieldValue): TextFieldValue {
        val cursor = prev.selection.min
        if (prev.selection.length != 0) return next
        if (cursor !in 0..prev.text.length) return next
        if (next.text.length != prev.text.length + 1) return next
        if (next.text.getOrNull(cursor) != '\n') return next
        if (next.text.removeRange(cursor, cursor + 1) != prev.text) return next

        val lineStart = prev.text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
        val line = prev.text.substring(lineStart, cursor)
        val prefix: String
        val markerLength: Int
        when {
            line.startsWith("- [ ] ") -> { prefix = "- [ ] "; markerLength = 6 }
            line.startsWith("- [x] ") || line.startsWith("- [X] ") -> { prefix = "- [ ] "; markerLength = 6 }
            line.startsWith("- ") -> { prefix = "- "; markerLength = 2 }
            else -> return next
        }
        val content = line.substring(markerLength)
        if (content.isBlank()) {
            // Пустой пункт: снять маркер, остаться на этой строке.
            val newText = prev.text.removeRange(lineStart, lineStart + markerLength)
            return TextFieldValue(newText, TextRange(lineStart))
        }
        // Продолжить список: маркер в начало новой строки (уже вставленной Enter).
        val insertAt = cursor + 1
        val newText = next.text.substring(0, insertAt) + prefix + next.text.substring(insertAt)
        return TextFieldValue(newText, TextRange(insertAt + prefix.length))
    }
}
