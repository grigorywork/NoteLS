package com.notessync.app.ui.notes.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.notessync.app.notes.text.FormatKind
import com.notessync.app.settings.AppFont
import com.notessync.app.ui.theme.appFontFamily

/** Shared by rich note ranges and whole table cells; callers supply validated values. */
internal fun formatSpanStyle(values: Map<FormatKind, String>, decoration: TextDecoration? = null): SpanStyle = SpanStyle(
    fontWeight = values[FormatKind.BOLD]?.let { if (it == "1") FontWeight.Bold else FontWeight.Normal },
    fontStyle = values[FormatKind.ITALIC]?.let { if (it == "1") FontStyle.Italic else FontStyle.Normal },
    fontSize = values[FormatKind.SIZE]?.toInt()?.sp ?: TextUnit.Unspecified,
    fontFamily = values[FormatKind.FONT]?.let { appFontFamily(AppFont.valueOf(it)) },
    color = values[FormatKind.COLOR]?.let { Color(it.toLong(16)) } ?: Color.Unspecified,
    background = values[FormatKind.HIGHLIGHT]?.let { Color(it.toLong(16)) } ?: Color.Unspecified,
    textDecoration = decoration)

internal fun formatParagraphStyle(values: Map<FormatKind, String>, indentStepSp: Int = 20) = ParagraphStyle(
    textAlign = when (values[FormatKind.ALIGN]) {
        "CENTER" -> TextAlign.Center; "END" -> TextAlign.End; "JUSTIFY" -> TextAlign.Justify; else -> TextAlign.Start
    }, textIndent = values[FormatKind.INDENT]?.toInt()?.let { TextIndent((it * indentStepSp).sp, (it * indentStepSp).sp) })

/** WCAG relative contrast, used only to keep inherited cell text readable on explicit fills. */
private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance(); val second = b.luminance()
    return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
}

internal fun tableCellTextStyle(base: TextStyle, values: Map<FormatKind, String>): TextStyle {
    val decorations = if (FormatKind.UNDERLINE in values || FormatKind.STRIKE in values) TextDecoration.combine(buildList {
        if (values[FormatKind.UNDERLINE] == "1") add(TextDecoration.Underline)
        if (values[FormatKind.STRIKE] == "1") add(TextDecoration.LineThrough)
    }) else null
    val span = formatSpanStyle(values, decorations)
    // An explicit fill must not leave theme-coloured text unreadable; only an unspecified colour is corrected.
    val color = span.color.takeIf { it != Color.Unspecified } ?: run {
        val background = span.background
        val inherited = base.color
        if (background != Color.Unspecified && inherited != Color.Unspecified && contrastRatio(inherited, background) < 4.5f)
            if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black
        else Color.Unspecified
    }
    val paragraph = formatParagraphStyle(values, indentStepSp = 12)
    val merged = base.merge(TextStyle(fontWeight = span.fontWeight, fontStyle = span.fontStyle,
        fontFamily = span.fontFamily, fontSize = span.fontSize, color = color,
        background = span.background, textDecoration = span.textDecoration,
        textAlign = paragraph.textAlign, textIndent = paragraph.textIndent))
    val size = values[FormatKind.SIZE]?.toFloatOrNull()
    return if (size == null) merged else merged.copy(lineHeight = maxOf(base.lineHeight.value, size * 1.35f).sp)
}
