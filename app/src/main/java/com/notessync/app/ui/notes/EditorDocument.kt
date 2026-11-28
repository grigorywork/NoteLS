package com.notessync.app.ui.notes

import com.notessync.app.notes.text.*
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.history.*
import androidx.compose.runtime.snapshots.Snapshot
import com.notessync.app.ui.notes.md.ParagraphEdit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.notessync.app.ui.notes.md.MarkdownEdit
import com.notessync.app.ui.notes.md.MarkdownLite
import java.util.UUID

internal class EditorDocument(initialHistoryEnabled: Boolean, private val canEdit: () -> Boolean,
    private val report: (EditorMessage) -> Unit) {
    var subtasks by mutableStateOf<List<com.notessync.app.notes.subtasks.Subtask>>(emptyList())
    var formatting by mutableStateOf(TextFormatting())
    var tables by mutableStateOf<NoteTables?>(NoteTables())
    var unsupportedTables = ""
    private var tableCell by mutableStateOf<TableCellRef?>(null)
    private var tableSelection: EditorSelection? = null
    val hasTables: Boolean get() = tables?.blocks?.isNotEmpty() == true || unsupportedTables.isNotEmpty()
    private var pendingFormatting by mutableStateOf<Map<FormatKind, String?>>(emptyMap())
    var titleField by mutableStateOf(TextFieldValue(""))
    val title: String get() = titleField.text
    var contentField by mutableStateOf(TextFieldValue(""))
    private val history = EditorHistory()
    private var atomicInput = false
    var historyEnabled by mutableStateOf(initialHistoryEnabled)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var editorField by mutableStateOf(EditorField.CONTENT)
    var historyRestoreVersion by mutableLongStateOf(0L)

    private fun snapshot() = EditorSnapshot(title, contentField.text, formatting, EditorCursor(
        EditorSelection(titleField.selection.start, titleField.selection.end),
        EditorSelection(contentField.selection.start, contentField.selection.end), editorField, pendingFormatting, tableCell, tableSelection), tables ?: NoteTables(), subtasks)

    private fun publishHistory() { canUndo = history.canUndo; canRedo = history.canRedo }

    private inline fun edit(kind: EditKind = EditKind.ATOMIC, change: () -> Unit) {
        if (!canEdit()) return
        val before = if (historyEnabled) snapshot() else null
        if (kind == EditKind.ATOMIC) history.breakGroup()
        Snapshot.withMutableSnapshot { change() }
        if (before != null && history.record(before, snapshot(), kind) == HistoryResult.TOO_LARGE) {
            report(EditorMessage.HISTORY_LIMIT)
        }
        publishHistory()
    }

    fun onEditorFocus(field: EditorField) {
        if (editorField != field) { history.breakGroup(); editorField = field }
    }

    fun onTitleChange(next: TextFieldValue) {
        if (!canEdit()) return
        onEditorFocus(EditorField.TITLE)
        val before = titleField
        if (before.text == next.text) {
            if (before.selection != next.selection || before.composition != next.composition) history.breakGroup()
            titleField = next
            return
        }
        edit(inputKind(before, next)) { titleField = next }
        if (before.composition != null && next.composition == null) history.breakGroup()
    }

    fun prepareClipboardEdit() { atomicInput = true; history.breakGroup() }

    /** IME composition is one group; bulk replacements, paste and Enter are separate edits. */
    private fun inputKind(before: TextFieldValue, next: TextFieldValue): EditKind {
        if (atomicInput) { atomicInput = false; return EditKind.ATOMIC }
        return when {
            before.composition != null || next.composition != null -> EditKind.COMPOSITION
            !before.selection.collapsed || !next.selection.collapsed -> EditKind.ATOMIC
            kotlin.math.abs(next.text.length - before.text.length) != 1 -> EditKind.ATOMIC
            else -> EditKind.INPUT
        }
    }

    fun onContentChange(newValue: TextFieldValue) {
        if (!canEdit()) return
        onEditorFocus(EditorField.CONTENT)
        val before = contentField
        if (before.text == newValue.text) {
            if (before.selection != newValue.selection || before.composition != newValue.composition) history.breakGroup()
            updateContent(newValue)
            return
        }
        val next = MarkdownEdit.handleEnter(before, newValue)
        edit(inputKind(before, next)) { updateContent(next) }
        if (before.composition != null && next.composition == null) history.breakGroup()
    }

    fun undo() = restoreHistory(backwards = true)
    fun redo() = restoreHistory(backwards = false)
    private fun restoreHistory(backwards: Boolean) {
        if (!historyEnabled || !canEdit()) return
        atomicInput = false
        val state = try { if (backwards) history.undo(snapshot()) else history.redo(snapshot()) }
        catch (_: SubtaskHistoryLimitReached) { report(EditorMessage.SUBTASK_LIMIT); return }
        state?.let {
            Snapshot.withMutableSnapshot {
                titleField = TextFieldValue(it.title, TextRange(it.cursor.title.start, it.cursor.title.end))
                contentField = TextFieldValue(it.content, TextRange(it.cursor.content.start, it.cursor.content.end))
                formatting = it.formatting; pendingFormatting = it.cursor.pending; editorField = it.cursor.field
                if (tables != null) tables = it.tables
                subtasks = it.subtasks
                tableCell = it.cursor.cell; tableSelection = it.cursor.cellSelection
                historyRestoreVersion++
            }
        }
        publishHistory()
    }

    private fun updateContent(next: TextFieldValue) {
        val before = contentField
        if (before.text == next.text) {
            if (before.selection != next.selection) pendingFormatting = emptyMap()
        } else {
            formatting = try { formatting.edited(before.text, next.text, before.selection.start, before.selection.end, pendingFormatting) }
            catch (_: FormattingLimitReached) {
                pendingFormatting = emptyMap(); report(EditorMessage.FORMATTING_LIMIT)
                formatting.edited(before.text, next.text, before.selection.start, before.selection.end)
            }
        }
        contentField = next
    }
    val activeFormatting: Map<FormatKind, String?>
        get() = formatting.at(contentField.selection.min, preferLeft = contentField.selection.collapsed) + pendingFormatting

    fun formatSelection(kind: FormatKind, value: String?) = edit { applySelectionFormat(kind, value) }
    private fun applySelectionFormat(kind: FormatKind, value: String?) {
        if (value != null && !kind.valid(value)) return
        val selection = contentField.selection
        if (selection.collapsed) pendingFormatting = pendingFormatting + (kind to value)
        else try { formatting = formatting.apply(selection.min, selection.max, kind, value) }
        catch (_: FormattingLimitReached) { report(EditorMessage.FORMATTING_LIMIT) }
    }
    fun selectAllContent() {
        history.breakGroup()
        pendingFormatting = emptyMap()
        contentField = contentField.copy(selection = TextRange(0, contentField.text.length))
    }
    fun toggleBold() = toggleTextStyle(FormatKind.BOLD)
    fun toggleItalic() = toggleTextStyle(FormatKind.ITALIC)
    fun toggleTextStyle(kind: FormatKind) {
        val legacy = when (kind) {
            FormatKind.BOLD -> MarkdownEdit.isInsideWrap(contentField.text, contentField.selection.min, "**")
            FormatKind.ITALIC -> MarkdownEdit.isInsideWrap(contentField.text, contentField.selection.min, "*")
            else -> false
        }
        val active = activeFormatting[kind]?.let { it == "1" } ?: legacy
        formatSelection(kind, if (active) "0" else "1")
    }
    private fun updateLines(next: TextFieldValue) = edit {
        formatting = formatting.editedLines(contentField.text, next.text)
        pendingFormatting = emptyMap(); contentField = next
    }
    fun toggleHeading(level: Int = 1) { updateLines(MarkdownEdit.toggleLinePrefix(contentField, "#".repeat(level.coerceIn(1, 3)) + " ")) }
    fun toggleList(kind: MarkdownEdit.ListKind = MarkdownEdit.ListKind.BULLET,
        alphabet: com.notessync.app.ui.notes.md.ListAlphabet = com.notessync.app.ui.notes.md.ListAlphabet.LATIN) {
        updateLines(MarkdownEdit.toggleListKind(contentField, kind, alphabet))
    }
    fun toggleChecklist() { updateLines(MarkdownEdit.toggleListKind(contentField, MarkdownEdit.ListKind.CHECKLIST)) }
    fun formatParagraph(kind: FormatKind, value: String) = edit {
        if (kind != FormatKind.ALIGN && kind != FormatKind.INDENT) return@edit
        val range = ParagraphEdit.bounds(contentField)
        if (range.isEmpty()) applySelectionFormat(kind, value)
        else try { formatting = formatting.apply(range.first, range.last + 1, kind, value) }
        catch (_: FormattingLimitReached) { report(EditorMessage.FORMATTING_LIMIT) }
    }
    fun changeIndent(delta: Int) {
        val level = activeFormatting[FormatKind.INDENT]?.toIntOrNull() ?: 0
        formatParagraph(FormatKind.INDENT, (level + delta).coerceIn(0, 4).toString())
    }
    fun toggleCheckboxAtLine(lineIndex: Int) {
        MarkdownLite.toggleCheckbox(contentField.text, lineIndex)?.let {
            // Длина текста не меняется (префиксы равной длины) — курсор остаётся на месте.
            edit { updateContent(TextFieldValue(it, contentField.selection)) }
        }
    }

    fun addSubtask(): String? {
        if (!canEdit() || subtasks.size >= com.notessync.app.notes.subtasks.Subtasks.MAX_ITEMS) {
            report(EditorMessage.SUBTASK_LIMIT); return null
        }
        val id = UUID.randomUUID().toString()
        edit { subtasks = subtasks + com.notessync.app.notes.subtasks.Subtask(id) }
        return id
    }
    fun editSubtask(id: String, text: String) {
        val next = subtasks.map { if (it.id == id) it.copy(text = text) else it }
        try { com.notessync.app.notes.subtasks.Subtasks.checked(next) }
        catch (_: IllegalArgumentException) { report(EditorMessage.SUBTASK_LIMIT); return }
        edit { subtasks = next }
    }
    fun removeSubtask(id: String) = edit { subtasks = subtasks.filterNot { it.id == id } }
    fun toggleSubtask(id: String, now: Long) = edit {
        subtasks = subtasks.map { if (it.id == id) it.paused(now).copy(completed = !it.completed) else it }
    }
    /** Clock events are not text undo steps; undoing text cannot resurrect a stale running intent. */
    fun changeSubtaskClock(id: String, change: (com.notessync.app.notes.subtasks.Subtask) -> com.notessync.app.notes.subtasks.Subtask) {
        if (!canEdit()) return
        val next = subtasks.map { if (it.id == id && !it.completed) change(it) else it }
        try { com.notessync.app.notes.subtasks.Subtasks.checked(next) }
        catch (_: IllegalArgumentException) { report(EditorMessage.SUBTASK_LIMIT); return }
        history.breakGroup(); subtasks = next
    }

    // ---------- Табличные блоки ----------

    fun addTable(rows: Int, columns: Int): String? {
        val current = tables ?: return null
        val id = UUID.randomUUID().toString()
        try {
            val next = current.add(id, rows, columns)
            edit { tables = next }
            return id.takeIf { tables == next }
        } catch (_: TableLimitReached) { report(EditorMessage.TABLE_LIMIT) }
        return null
    }
    fun removeTable(id: String) { tables?.let { value -> edit { tables = value.remove(id) } } }
    fun focusTableCell(ref: TableCellRef) {
        onEditorFocus(EditorField.TABLE)
        if (tableCell != ref) history.breakGroup()
        tableCell = ref
    }
    fun editTableCell(ref: TableCellRef, value: String) {
        val old = tables?.find(ref.tableId)?.takeIf { it.contains(ref.row, ref.column) }?.cells?.get(ref.row)?.get(ref.column) ?: return
        val range = tableSelection?.takeIf { tableCell == ref }?.let { TextRange(it.start, it.end) } ?: TextRange(old.length)
        onTableCellChange(ref, TextFieldValue(old, range), TextFieldValue(value, TextRange(value.length)))
    }
    fun onTableCellChange(ref: TableCellRef, before: TextFieldValue, next: TextFieldValue) {
        val current = tables ?: return
        val table = current.find(ref.tableId) ?: return
        if (!table.contains(ref.row, ref.column)) return
        focusTableCell(ref)
        tableSelection = EditorSelection(before.selection.start, before.selection.end)
        if (table.cells[ref.row][ref.column] == next.text) {
            if (before.selection != next.selection || before.composition != next.composition) history.breakGroup()
            tableSelection = EditorSelection(next.selection.start, next.selection.end)
            return
        }
        val composing = before.composition != null || next.composition != null
        val kind = if (!atomicInput && (composing || (before.selection.collapsed &&
            kotlin.math.abs(next.text.length - before.text.length) == 1))) EditKind.TABLE_INPUT else EditKind.ATOMIC
        atomicInput = false
        try {
            val changed = current.editCell(ref, next.text)
            edit(kind) { tables = changed; tableSelection = EditorSelection(next.selection.start, next.selection.end) }
        } catch (_: TableLimitReached) { report(EditorMessage.TABLE_LIMIT) }
        if (before.composition != null && next.composition == null) history.breakGroup()
    }
    fun formatTableCells(ref: TableCellRef, scope: TableFormatScope, kind: FormatKind, value: String?) {
        if (!canEdit()) return
        val current = tables ?: return
        try {
            val changed = current.formatCells(ref, scope, kind, value)
            if (changed == current) return
            focusTableCell(ref)
            edit { tables = changed }
        } catch (_: TableLimitReached) { report(EditorMessage.TABLE_LIMIT) }
    }
    fun clearTableStyles(ref: TableCellRef, scope: TableFormatScope) {
        if (!canEdit()) return
        val current = tables ?: return
        val changed = current.clearStyles(ref, scope)
        if (changed != current) { focusTableCell(ref); edit { tables = changed } }
    }
    fun resizeTableColumn(id: String, column: Int, width: Int) {
        tables?.let { value -> edit { tables = value.resizeColumn(id, column, width) } }
    }
    fun resizeTableRow(id: String, row: Int, height: Int) {
        tables?.let { value -> edit { tables = value.resizeRow(id, row, height) } }
    }
    val focusedTableCell: TableCellRef? get() = tableCell
    val focusedTableSelection: EditorSelection? get() = tableSelection

    // ---------- Голосовой ввод (Этап 32) ----------

    /** Вставка распознанной речи в позицию курсора с выбранной структуризацией. */
    fun insertVoiceText(raw: String, structure: com.notessync.app.settings.VoiceStructure) {
        val processed = when (structure) {
            com.notessync.app.settings.VoiceStructure.RAW ->
                com.notessync.app.voice.TextStructurer.normalize(raw)
            com.notessync.app.settings.VoiceStructure.POINTS ->
                com.notessync.app.voice.TextStructurer.toPoints(raw)
            com.notessync.app.settings.VoiceStructure.MAIN_IDEA ->
                com.notessync.app.voice.TextStructurer.mainIdea(raw)
        }
        if (processed.isBlank()) return
        val current = contentField
        val text = current.text
        val start = current.selection.min.coerceIn(0, text.length)
        val end = current.selection.max.coerceIn(0, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val isList = structure == com.notessync.app.settings.VoiceStructure.POINTS
        val prefix = when {
            before.isEmpty() || before.endsWith("\n") -> ""
            isList -> "\n"
            before.endsWith(" ") -> ""
            else -> " "
        }
        val suffix = when {
            after.isEmpty() -> ""
            isList && !after.startsWith("\n") -> "\n"
            !isList && after.first().isLetterOrDigit() -> " "
            else -> ""
        }
        val inserted = prefix + processed + suffix
        onEditorFocus(EditorField.CONTENT)
        edit { updateContent(TextFieldValue(
            before + inserted + after,
            TextRange((before + inserted).length)
        )) }
    }


    fun clearHistory() { history.clear(); publishHistory() }
    fun releaseHistory() { history.clear() }
    fun replaceRange(body: String, start: Int, end: Int, replacement: String) {
        onEditorFocus(EditorField.CONTENT)
        edit {
            val next = body.replaceRange(start, end, replacement)
            contentField = contentField.copy(selection = TextRange(start, end), composition = null)
            updateContent(TextFieldValue(next, TextRange(start + replacement.length)))
        }
    }
}
