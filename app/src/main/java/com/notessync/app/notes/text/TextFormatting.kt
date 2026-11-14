package com.notessync.app.notes.text

import com.notessync.app.settings.AppFont
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Plain content plus platform-neutral UTF-16 ranges. No HTML, remote fonts or executable markup. */
@Serializable data class FormatMark(val start: Int, val end: Int, val kind: String, val value: String)
data class FormattingRun(val start: Int, val end: Int, val values: Map<FormatKind, String>)
class FormattingLimitReached : IllegalStateException("Too many formatting ranges")
enum class FormatKind {
    BOLD, ITALIC, UNDERLINE, STRIKE, SIZE, FONT, COLOR, HIGHLIGHT, ALIGN, INDENT;
    fun valid(value: String): Boolean = when (this) {
        BOLD, ITALIC, UNDERLINE, STRIKE -> value == "0" || value == "1"
        SIZE -> value.toIntOrNull()?.let { it in 12..48 } == true
        FONT -> AppFont.entries.any { it.name == value }
        ALIGN -> value in listOf("START", "CENTER", "END", "JUSTIFY")
        INDENT -> value.toIntOrNull()?.let { it in 0..4 } == true
        COLOR, HIGHLIGHT -> value.length == 8 && value.startsWith("FF") && value.all { it in '0'..'9' || it in 'A'..'F' }
    }
}

@Serializable
data class TextFormatting(val version: Int = 1, val marks: List<FormatMark> = emptyList()) {
    fun encode(): String = if (marks.isEmpty()) "" else json.encodeToString(this)
    fun at(offset: Int, preferLeft: Boolean = true): Map<FormatKind, String> {
        val inside = marks.filter { it.start <= offset && it.end > offset }
        val left = if (preferLeft) marks.filter { it.start < offset && it.end == offset } else emptyList()
        return (inside + left).associate { FormatKind.valueOf(it.kind) to it.value }
    }

    /** Resolve all intervals with a sweep, bounded by the number of marks, not text length. */
    fun runs(): List<FormattingRun> {
        val starts = marks.withIndex().groupBy { it.value.start }
        val ends = marks.withIndex().groupBy { it.value.end }
        val active = mutableMapOf<FormatKind, java.util.TreeMap<Int, String>>()
        return buildList {
            (starts.keys + ends.keys).sorted().zipWithNext().forEach { (start, end) ->
                ends[start].orEmpty().forEach { (index, mark) -> active[FormatKind.valueOf(mark.kind)]?.remove(index) }
                starts[start].orEmpty().forEach { (index, mark) ->
                    active.getOrPut(FormatKind.valueOf(mark.kind)) { java.util.TreeMap() }[index] = mark.value
                }
                val values = active.mapNotNull { (kind, entries) -> entries.lastEntry()?.let { kind to it.value } }.toMap()
                if (values.isNotEmpty()) add(FormattingRun(start, end, values))
            }
        }
    }

    fun apply(start: Int, end: Int, kind: FormatKind, value: String?): TextFormatting {
        if (start >= end || start < 0 || (value != null && !kind.valid(value))) return this
        val result = marks.flatMap { mark ->
            if (mark.kind != kind.name || mark.end <= start || mark.start >= end) listOf(mark)
            else buildList {
                if (mark.start < start) add(mark.copy(end = start))
                if (mark.end > end) add(mark.copy(start = end))
            }
        }.toMutableList()
        if (value != null) result.add(FormatMark(start, end, kind.name, value))
        return normalized(result).bounded()
    }

    /** Anchor repeated letters to the actual selection, with a diff fallback for IME replacements. */
    fun edited(old: String, next: String, selectionStart: Int, selectionEnd: Int,
               pending: Map<FormatKind, String?> = emptyMap()): TextFormatting {
        if (old == next) return this
        var start = minOf(selectionStart, selectionEnd).coerceIn(0, old.length)
        var end = maxOf(selectionStart, selectionEnd).coerceIn(start, old.length)
        val insertedHint = next.length - old.length + end - start
        val anchored = insertedHint >= 0 && start + insertedHint <= next.length &&
            next.regionMatches(0, old, 0, start) && next.regionMatches(start + insertedHint, old, end, old.length - end)
        if (!anchored) {
            start = 0
            while (start < minOf(old.length, next.length) && old[start] == next[start]) start++
            var tail = 0
            while (tail < old.length - start && tail < next.length - start &&
                old[old.lastIndex - tail] == next[next.lastIndex - tail]) tail++
            end = old.length - tail
        }
        val inserted = next.length - old.length + end - start
        val delta = next.length - old.length
        val result = buildList {
            marks.forEach { mark ->
                if (mark.start < start) {
                    val leftEnd = minOf(mark.end, start)
                    if (mark.start < leftEnd) add(mark.copy(end = leftEnd))
                }
                if (mark.end > end) {
                    val rightStart = maxOf(mark.start, end) + delta
                    if (rightStart < mark.end + delta) add(mark.copy(start = rightStart, end = mark.end + delta))
                }
            }
            if (inserted > 0) {
                val values = at(start, preferLeft = start == end).mapValues { it.value as String? }.toMutableMap()
                values.putAll(pending)
                values.forEach { (kind, value) -> if (value != null && kind.valid(value))
                    add(FormatMark(start, start + inserted, kind.name, value)) }
            }
        }
        return normalized(result).bounded()
    }

    /** A multi-line prefix operation must preserve separate styles inside every line. */
    fun editedLines(old: String, next: String): TextFormatting {
        val before = old.split('\n'); val after = next.split('\n')
        if (before.size != after.size) return edited(old, next, 0, 0)
        val offsets = IntArray(before.size)
        for (i in 1 until before.size) offsets[i] = offsets[i - 1] + before[i - 1].length + 1
        var result = this; var current = old
        before.indices.reversed().forEach { i ->
            if (before[i] != after[i]) {
                var prefix = 0
                while (prefix < minOf(before[i].length, after[i].length) && before[i][prefix] == after[i][prefix]) prefix++
                var suffix = 0
                while (suffix < before[i].length - prefix && suffix < after[i].length - prefix &&
                    before[i][before[i].lastIndex - suffix] == after[i][after[i].lastIndex - suffix]) suffix++
                val start = offsets[i] + prefix; val end = offsets[i] + before[i].length - suffix
                val changed = current.replaceRange(start, end, after[i].substring(prefix, after[i].length - suffix))
                result = result.edited(current, changed, start, end); current = changed
            }
        }
        return result
    }

    private fun bounded(): TextFormatting {
        if (marks.size > MAX_MARKS) throw FormattingLimitReached()
        return this
    }
    companion object {
        const val MAX_MARKS = 2000
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(raw: String, textLength: Int): TextFormatting = decodeInternal(raw, textLength, null)
        fun decodePreview(raw: String, text: String): TextFormatting = decodeInternal(raw, text.length, text)
        private fun decodeInternal(raw: String, textLength: Int, preview: String?): TextFormatting {
            if (raw.isBlank() || raw.length > 250_000) return TextFormatting()
            return runCatching {
                val parsed = json.decodeFromString<TextFormatting>(raw)
                if (parsed.version != 1) return TextFormatting()
                val valid = parsed.marks.take(MAX_MARKS).mapNotNull { mark ->
                    val kind = FormatKind.entries.firstOrNull { it.name == mark.kind }
                    if (kind == null || !kind.valid(mark.value) || mark.start < 0 || mark.start >= mark.end ||
                        mark.start >= textLength || (preview == null && mark.end > textLength)) return@mapNotNull null
                    var start = mark.start
                    var end = minOf(mark.end, textLength)
                    if (preview != null) {
                        if (start > 0 && preview[start - 1].isHighSurrogate() && preview[start].isLowSurrogate()) start--
                        if (end < preview.length && preview[end - 1].isHighSurrogate() && preview[end].isLowSurrogate()) end++
                    }
                    mark.copy(start = start, end = end)
                }
                normalized(TextFormatting(marks = valid).runs().flatMap { run ->
                    run.values.map { (kind, value) -> FormatMark(run.start, run.end, kind.name, value) }
                }).bounded()
            }.getOrDefault(TextFormatting())
        }
        private fun normalized(marks: List<FormatMark>): TextFormatting {
            val result = mutableListOf<FormatMark>()
            marks.sortedWith(compareBy({ it.kind }, { it.value }, { it.start })).forEach { mark ->
                val last = result.lastOrNull()
                if (last != null && last.kind == mark.kind && last.value == mark.value && last.end >= mark.start)
                    result[result.lastIndex] = last.copy(end = maxOf(last.end, mark.end))
                else result.add(mark)
            }
            return TextFormatting(marks = result)
        }
    }
}
