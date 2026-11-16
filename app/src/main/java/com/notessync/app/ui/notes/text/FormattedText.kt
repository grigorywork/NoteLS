package com.notessync.app.ui.notes.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.notessync.app.notes.text.*
import com.notessync.app.settings.AppFont
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.notes.md.MarkdownVisualTransformation
import com.notessync.app.ui.theme.appFontFamily

class FormattedTextTransformation(private val formatting: TextFormatting) : VisualTransformation {
    private val markdown = MarkdownVisualTransformation()
    private val runs = formatting.runs()
    private val paragraphs = runs.filter { FormatKind.ALIGN in it.values || FormatKind.INDENT in it.values }
    private var lastSource: AnnotatedString? = null
    private var lastResult: TransformedText? = null
    override fun filter(text: AnnotatedString): TransformedText {
        if (text == lastSource) lastResult?.let { return it }
        return render(text).also { lastSource = text; lastResult = it }
    }
    private fun render(text: AnnotatedString): TransformedText {
        val base = markdown.filter(text)
        if (formatting.marks.isEmpty()) return base
        // ParagraphStyle supplies its own visual break. Keep a zero-width one-character anchor
        // for the source newline so cursor offsets remain unchanged without a second break.
        val builder = AnnotatedString.Builder(if (paragraphs.isNotEmpty())
            AnnotatedString(base.text.text.replace('\n', '\u200B'), base.text.spanStyles) else base.text)
        val baseCuts = java.util.TreeSet(base.text.spanStyles.flatMap {
            listOf(base.offsetMapping.transformedToOriginal(it.start), base.offsetMapping.transformedToOriginal(it.end))
        })
        runs.flatMap { run -> (listOf(run.start) + baseCuts.subSet(run.start, false, run.end, false) + run.end)
            .zipWithNext().map { (a, b) -> FormattingRun(a, b, run.values) } }.forEach { (start, end, values) ->
            if (start >= 0 && end <= text.length && values.keys.any { it != FormatKind.ALIGN && it != FormatKind.INDENT }) {
                val a = base.offsetMapping.originalToTransformed(start); val b = base.offsetMapping.originalToTransformed(end)
                val inherited = base.text.spanStyles.filter { it.start < b && it.end > a }.mapNotNull { it.item.textDecoration }
                val decorations = if (FormatKind.UNDERLINE in values || FormatKind.STRIKE in values) TextDecoration.combine(buildList {
                    if (values[FormatKind.UNDERLINE] == "1" || (values[FormatKind.UNDERLINE] != "0" && inherited.any { it.contains(TextDecoration.Underline) })) add(TextDecoration.Underline)
                    if (values[FormatKind.STRIKE] == "1" || (values[FormatKind.STRIKE] != "0" && inherited.any { it.contains(TextDecoration.LineThrough) })) add(TextDecoration.LineThrough)
                }) else null
                if (a < b) builder.addStyle(formatSpanStyle(values, decorations), a, b)
            }
        }
        if (paragraphs.isNotEmpty()) {
            var runIndex = 0; var lineStart = 0
            text.text.split('\n').forEach { line ->
                val end = lineStart + line.length
                while (runIndex < paragraphs.size && paragraphs[runIndex].end <= lineStart) runIndex++
                var cursor = runIndex
                val values = mutableMapOf<FormatKind, String>()
                while (cursor < paragraphs.size && paragraphs[cursor].start < end) {
                    values.putAll(paragraphs[cursor].values)
                    if (paragraphs[cursor].end > end) break
                    cursor++
                }
                if (line.isEmpty()) values.putAll(formatting.at(lineStart))
                runIndex = cursor
                builder.addStyle(formatParagraphStyle(values),
                    base.offsetMapping.originalToTransformed(lineStart),
                    base.offsetMapping.originalToTransformed(if (end < text.length) end + 1 else end))
                lineStart = end + 1
            }
        }
        return TransformedText(builder.toAnnotatedString(), base.offsetMapping)
    }
}

const val SOURCE_LINE_ANNOTATION = "nls:sourceLine"

fun formattedPreview(text: String, formatting: TextFormatting, hideCompleted: Boolean = false): AnnotatedString {
    val rendered = FormattedTextTransformation(formatting).filter(AnnotatedString(text))
    val visible = MarkdownLite.visibleSourceLines(text, hideCompleted).toSet()
    val builder = AnnotatedString.Builder()
    val lastVisible = visible.maxOrNull()
    var start = 0
    text.split('\n').forEachIndexed { index, line ->
        if (index in visible) {
            val end = start + line.length + if (index != lastVisible && start + line.length < text.length) 1 else 0
            val outputStart = builder.length
            val slice = rendered.text.subSequence(rendered.offsetMapping.originalToTransformed(start), rendered.offsetMapping.originalToTransformed(end))
            // Do not duplicate the empty final paragraph at both neighbouring slice boundaries.
            val paragraphStyles = if (start == end) slice.paragraphStyles.filter { it.start == 0 && it.end == 0 }.takeLast(1)
                else slice.paragraphStyles.filter { it.start < it.end }
            builder.append(AnnotatedString(slice.text, slice.spanStyles, paragraphStyles))
            builder.addStringAnnotation(SOURCE_LINE_ANNOTATION, index.toString(), outputStart, builder.length)
        }
        start += line.length + 1
    }
    return builder.toAnnotatedString()
}
