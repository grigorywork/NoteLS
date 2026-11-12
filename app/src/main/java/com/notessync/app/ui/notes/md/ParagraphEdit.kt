package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

object ParagraphEdit {
    fun bounds(value: TextFieldValue): IntRange {
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(0, value.text.length)
        val last = if (end > start && value.text.getOrNull(end - 1) == '\n') end - 1 else end
        val from = value.text.lastIndexOf('\n', start - 1) + 1
        val to = value.text.indexOf('\n', last).let { if (it < 0) value.text.length else it }
        return from until to
    }
    fun transform(value: TextFieldValue, operation: (List<String>) -> List<String>): TextFieldValue {
        val range = bounds(value)
        val block = operation(value.text.substring(range.first, range.last + 1).split('\n')).joinToString("\n")
        val text = value.text.replaceRange(range.first, range.last + 1, block)
        val end = range.first + block.length
        return TextFieldValue(text, if (value.selection.collapsed) TextRange(end) else TextRange(range.first, end))
    }
}

data class ListMarker(val kind: MarkdownEdit.ListKind, val length: Int, val ordinal: Int = 1, val uppercase: Boolean = false)
object ListMarkers {
    private val number = Regex("^([0-9]{1,6})[.)] ")
    // Parentheses avoid interpreting ordinary English sentences such as "Note. ..." as lists.
    private val letter = Regex("^([a-zA-Z]{1,4})\\) ")
    fun parse(line: String): ListMarker? = when {
        line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") -> ListMarker(MarkdownEdit.ListKind.CHECKLIST, 6)
        line.startsWith("- ") -> ListMarker(MarkdownEdit.ListKind.BULLET, 2)
        else -> number.find(line)?.let { ListMarker(MarkdownEdit.ListKind.NUMBER, it.value.length, it.groupValues[1].toInt()) }
            ?: letter.find(line)?.let { ListMarker(MarkdownEdit.ListKind.LETTER, it.value.length,
                it.groupValues[1].lowercase().fold(0) { total, char -> total * 26 + char.code - 'a'.code + 1 },
                it.groupValues[1].first().isUpperCase()) }
    }
    fun prefix(kind: MarkdownEdit.ListKind, ordinal: Int = 1, uppercase: Boolean = false): String = when (kind) {
        MarkdownEdit.ListKind.BULLET -> "- "
        MarkdownEdit.ListKind.CHECKLIST -> "- [ ] "
        MarkdownEdit.ListKind.NUMBER -> "${ordinal.coerceIn(1, 999999)}. "
        MarkdownEdit.ListKind.LETTER -> {
            var number = ordinal.coerceIn(1, 475254); var result = ""
            while (number > 0) { number--; result = ('a' + number % 26) + result; number /= 26 }
            (if (uppercase) result.uppercase() else result) + ") "
        }
    }
}
