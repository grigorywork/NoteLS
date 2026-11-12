package com.notessync.app.notes.history

import com.notessync.app.notes.text.FormatKind
import com.notessync.app.notes.text.TextFormatting

/** Session-only history. No platform, persistence, clock polling, or retained full-text snapshots. */
enum class EditorField { TITLE, CONTENT }
data class EditorSelection(val start: Int = 0, val end: Int = start) {
    fun bounded(length: Int) = EditorSelection(start.coerceIn(0, length), end.coerceIn(0, length))
}
data class EditorCursor(
    val title: EditorSelection = EditorSelection(),
    val content: EditorSelection = EditorSelection(),
    val field: EditorField = EditorField.CONTENT,
    val pending: Map<FormatKind, String?> = emptyMap()
)
data class EditorSnapshot(
    val title: String, val content: String, val formatting: TextFormatting, val cursor: EditorCursor
)
enum class EditKind { INPUT, COMPOSITION, ATOMIC }
enum class HistoryResult { UNCHANGED, RECORDED, TOO_LARGE }

class EditorHistory(
    private val maxSteps: Int = 50,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init { require(maxSteps > 0 && maxBytes > 0) }
    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()
    private var mergeOpen = false
    var estimatedBytes: Long = 0; private set
    val stepCount: Int get() = undo.size + redo.size
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun breakGroup() { mergeOpen = false }
    fun clear() { undo.clear(); redo.clear(); estimatedBytes = 0; breakGroup() }

    fun record(before: EditorSnapshot, after: EditorSnapshot, kind: EditKind): HistoryResult {
        val title = Patch.between(before.title, after.title, before.cursor.title, after.cursor.title)
        val content = Patch.between(before.content, after.content, before.cursor.content, after.cursor.content)
        if (title == null && content == null && before.formatting == after.formatting &&
            before.cursor.pending == after.cursor.pending) return HistoryResult.UNCHANGED
        val entry = Entry(title, content, before.formatting, after.formatting,
            before.cursor, after.cursor, kind, clockMillis())
        redo.forEach { estimatedBytes -= it.bytes }; redo.clear()
        if (entry.bytes > maxBytes) { clear(); return HistoryResult.TOO_LARGE }
        val previous = undo.lastOrNull()
        val merged = if (mergeOpen && previous != null) previous.merge(entry) else null
        if (merged != null && merged.bytes <= maxBytes) {
            estimatedBytes -= undo.removeLast().bytes
            if (merged.isNoOp) { breakGroup(); return HistoryResult.RECORDED }
            undo.addLast(merged); estimatedBytes += merged.bytes
        } else {
            undo.addLast(entry); estimatedBytes += entry.bytes
        }
        while (undo.size > maxSteps || estimatedBytes > maxBytes) estimatedBytes -= undo.removeFirst().bytes
        mergeOpen = kind != EditKind.ATOMIC
        return HistoryResult.RECORDED
    }

    fun undo(current: EditorSnapshot): EditorSnapshot? {
        breakGroup()
        val entry = undo.removeLastOrNull() ?: return null
        redo.addLast(entry)
        return entry.apply(current, backwards = true)
    }
    fun redo(current: EditorSnapshot): EditorSnapshot? {
        breakGroup()
        val entry = redo.removeLastOrNull() ?: return null
        undo.addLast(entry)
        return entry.apply(current, backwards = false)
    }

    private data class Patch(val start: Int, val removed: String, val inserted: String) {
        fun apply(text: String, backwards: Boolean): String = if (backwards)
            text.replaceRange(start, start + inserted.length, removed)
        else text.replaceRange(start, start + removed.length, inserted)

        fun merge(next: Patch, composition: Boolean): Patch? = when {
            // An IME may replace its own intermediate spelling inside this group's insertion.
            composition && next.start >= start && next.start + next.removed.length <= start + inserted.length ->
                copy(inserted = inserted.replaceRange(next.start - start, next.start - start + next.removed.length, next.inserted))
            removed.isEmpty() && next.removed.isEmpty() && next.start == start + inserted.length ->
                copy(inserted = inserted + next.inserted)
            inserted.isEmpty() && next.inserted.isEmpty() && next.start + next.removed.length == start ->
                Patch(next.start, next.removed + removed, "")
            inserted.isEmpty() && next.inserted.isEmpty() && next.start == start ->
                copy(removed = removed + next.removed)
            else -> null
        }

        companion object {
            fun between(old: String, next: String, selection: EditorSelection, nextSelection: EditorSelection): Patch? {
                if (old == next) return null
                var start = minOf(selection.start, selection.end).coerceIn(0, old.length)
                var end = maxOf(selection.start, selection.end).coerceIn(start, old.length)
                // Anchor backspace/delete without scanning a long document for every key.
                if (start == end && next.length < old.length) {
                    val deleted = old.length - next.length
                    start = nextSelection.start.coerceIn(0, old.length)
                    end = (start.toLong() + deleted).coerceAtMost(old.length.toLong()).toInt()
                }
                val size = next.length - old.length + end - start
                if (size < 0 || start + size > next.length || !next.regionMatches(0, old, 0, start) ||
                    !next.regionMatches(start + size, old, end, old.length - end)) {
                    start = 0
                    while (start < minOf(old.length, next.length) && old[start] == next[start]) start++
                    var tail = 0
                    while (tail < old.length - start && tail < next.length - start &&
                        old[old.lastIndex - tail] == next[next.lastIndex - tail]) tail++
                    end = old.length - tail
                }
                return Patch(start, old.substring(start, end), next.substring(start, next.length - (old.length - end)))
            }
        }
    }

    private data class Entry(
        val title: Patch?, val content: Patch?,
        val beforeFormat: TextFormatting, val afterFormat: TextFormatting,
        val beforeCursor: EditorCursor, val afterCursor: EditorCursor,
        val kind: EditKind, val time: Long
    ) {
        // Conservative accounting budget, not a measurement of ART/PSS. Shared marks are charged twice.
        val bytes: Long = 256L + listOfNotNull(title, content).sumOf {
            64L + 2L * (it.removed.length.toLong() + it.inserted.length)
        } + listOf(beforeFormat, afterFormat).sumOf { format -> format.marks.sumOf {
            128L + 2L * (it.kind.length + it.value.length)
        } } + listOf(beforeCursor, afterCursor).sumOf { cursor -> cursor.pending.entries.sumOf {
            96L + 2L * (it.value?.length ?: 0)
        } }

        val isNoOp: Boolean get() = listOfNotNull(title, content).all { it.removed == it.inserted } &&
            beforeFormat == afterFormat && beforeCursor.pending == afterCursor.pending

        fun apply(current: EditorSnapshot, backwards: Boolean): EditorSnapshot {
            val newTitle = title?.apply(current.title, backwards) ?: current.title
            val newContent = content?.apply(current.content, backwards) ?: current.content
            val cursor = if (backwards) beforeCursor else afterCursor
            return EditorSnapshot(newTitle, newContent, if (backwards) beforeFormat else afterFormat,
                cursor.copy(title = cursor.title.bounded(newTitle.length), content = cursor.content.bounded(newContent.length)))
        }
        fun merge(next: Entry): Entry? {
            if (kind == EditKind.ATOMIC || kind != next.kind || next.time - time !in 0..1_000 ||
                afterCursor != next.beforeCursor || beforeCursor.pending != next.afterCursor.pending) return null
            val titleEdit = title != null && content == null && next.title != null && next.content == null
            val contentEdit = content != null && title == null && next.content != null && next.title == null
            val combined = when {
                titleEdit -> title!!.merge(next.title!!, kind == EditKind.COMPOSITION)
                contentEdit -> content!!.merge(next.content!!, kind == EditKind.COMPOSITION)
                else -> null
            } ?: return null
            if (combined.removed.length + combined.inserted.length > 256 ||
                '\n' in combined.removed || '\n' in combined.inserted) return null
            return copy(title = if (titleEdit) combined else null, content = if (contentEdit) combined else null,
                afterFormat = next.afterFormat, afterCursor = next.afterCursor, time = next.time)
        }
    }
}
