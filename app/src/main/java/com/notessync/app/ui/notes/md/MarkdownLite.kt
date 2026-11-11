package com.notessync.app.ui.notes.md

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em

/**
 * Облегчённый markdown для заметок (Этап 3).
 * Построчная разметка: «# »/«## » заголовки, «- » список, «- [ ]»/«- [x]» чек-бокс.
 * Внутристрочная: **жирный**, *курсив*.
 * Важно: разбор не меняет число строк — строка исходника = строка результата,
 * поэтому тап по строке в предпросмотре точно находит строку в исходнике.
 * Этап 27: выполненный пункт — зелёный и зачёркнутый; при [hideCompleted]
 * строки выполненных опускаются, а [visibleSourceLines] хранит соответствие
 * видимых строк строкам исходника.
 */
object MarkdownLite {

    /** Цвет выполненного пункта (зелёный, читается на светлой и тёмной теме). */
    val CompletedColor = Color(0xFF4CAF50)

    /** Разбирает текст в AnnotatedString; [hideCompleted] прячет выполненные чекбоксы. */
    fun parse(text: String, hideCompleted: Boolean = false): AnnotatedString {
        val lines = text.split('\n')
        return buildAnnotatedString {
            var first = true
            lines.forEach { raw ->
                if (hideCompleted && isCompleted(raw)) return@forEach
                if (!first) append('\n')
                first = false
                when {
                    raw.startsWith(CHECKBOX_DONE) || raw.startsWith(CHECKBOX_DONE_UPPER) -> {
                        withStyle(SpanStyle(color = CompletedColor)) { append("☑ ") }
                        withStyle(
                            SpanStyle(
                                color = CompletedColor,
                                textDecoration = TextDecoration.LineThrough
                            )
                        ) { appendInline(raw.substring(CHECKBOX_DONE.length)) }
                    }
                    raw.startsWith(CHECKBOX_TODO) -> {
                        appendStyledLine("☐ " + raw.removePrefix(CHECKBOX_TODO), isCheckbox = true)
                    }
                    raw.startsWith("## ") -> appendStyledLine(raw.removePrefix("## "), sizeEm = 1.35f, weight = FontWeight.Bold)
                    raw.startsWith("# ") -> appendStyledLine(raw.removePrefix("# "), sizeEm = 1.6f, weight = FontWeight.Bold)
                    raw.startsWith("- ") -> appendInline("• " + raw.removePrefix("- "))
                    else -> appendInline(raw)
                }
            }
        }
    }

    /**
     * Соответствие видимых строк строкам исходника для [parse] с тем же
     * [hideCompleted]: visibleSourceLines[i] = индекс строки исходника.
     * Нужно, чтобы тап по строке предпросмотра находил исходную строку.
     */
    fun visibleSourceLines(text: String, hideCompleted: Boolean): IntArray {
        val lines = text.split('\n')
        if (!hideCompleted) return IntArray(lines.size) { it }
        return lines.indices.filter { !isCompleted(lines[it]) }.toIntArray()
    }

    /** Переключает чек-бокс в строке [lineIndex] (0-based). null — в этой строке его нет. */
    fun toggleCheckbox(text: String, lineIndex: Int): String? {
        val lines = text.split('\n').toMutableList()
        if (lineIndex !in lines.indices) return null
        val line = lines[lineIndex]
        lines[lineIndex] = when {
            line.startsWith(CHECKBOX_TODO) -> CHECKBOX_DONE + line.removePrefix(CHECKBOX_TODO)
            line.startsWith(CHECKBOX_DONE) -> CHECKBOX_TODO + line.removePrefix(CHECKBOX_DONE)
            line.startsWith(CHECKBOX_DONE_UPPER) -> CHECKBOX_TODO + line.substring(CHECKBOX_DONE_UPPER.length)
            else -> return null
        }
        return lines.joinToString("\n")
    }

    /** true, если в тексте есть хотя бы один чек-бокс. */
    fun hasCheckboxes(text: String): Boolean =
        text.lineSequence().any {
            it.startsWith(CHECKBOX_TODO) || it.startsWith(CHECKBOX_DONE) ||
                it.startsWith(CHECKBOX_DONE_UPPER)
        }

    private fun AnnotatedString.Builder.appendStyledLine(
        text: String,
        isCheckbox: Boolean = false,
        sizeEm: Float = 1f,
        weight: FontWeight? = null
    ) {
        val style = SpanStyle(
            fontSize = sizeEm.em,
            fontWeight = weight,
            fontStyle = if (isCheckbox) FontStyle.Normal else null
        )
        withStyle(style) { appendInline(text) }
    }

    // Внутристрочная разметка: ***жирный курсив***, **жирный**, *курсив*.
    // Маркеры применяются только при корректных границах: содержимое непустое
    // и без пробелов по краям, поэтому «2 * 3 * 4» остаётся формулой.
    private fun AnnotatedString.Builder.appendInline(text: String) {
        fun validInner(from: Int, to: Int): Boolean =
            from < to && !text[from].isWhitespace() && !text[to - 1].isWhitespace()

        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i + 3 && validInner(i + 3, end)) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            appendInline(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append('*'); i += 1
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2 && validInner(i + 2, end)) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendInline(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append('*'); i += 1
                    }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && validInner(i + 1, end)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendInline(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('*'); i += 1
                    }
                }
                else -> {
                    val next = text.indexOf('*', i)
                    if (next < 0) {
                        append(text.substring(i)); i = text.length
                    } else {
                        append(text.substring(i, next)); i = next
                    }
                }
            }
        }
    }

    private const val CHECKBOX_TODO = "- [ ] "
    private const val CHECKBOX_DONE = "- [x] "
    private const val CHECKBOX_DONE_UPPER = "- [X] "

    private fun isCompleted(line: String): Boolean =
        line.startsWith(CHECKBOX_DONE) || line.startsWith(CHECKBOX_DONE_UPPER)
}
