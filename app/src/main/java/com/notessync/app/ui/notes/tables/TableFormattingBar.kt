package com.notessync.app.ui.notes.tables

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.text.FormatKind
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.text.*

@Composable
internal fun TableFormattingBar(table: NoteTable, viewModel: NoteEditorViewModel, onGeometry: () -> Unit) {
    val ref = viewModel.focusedTableCell?.takeIf { it.tableId == table.id && table.contains(it.row, it.column) }
        ?: TableCellRef(table.id, 0, 0)
    var scope by rememberSaveable(table.id) { mutableStateOf(TableFormatScope.CELL) }
    var chooseScope by remember(table.id) { mutableStateOf(false) }
    var popup by remember(table.id, ref, scope) { mutableStateOf<FormatPopup?>(null) }
    val active = remember(table.styles, table.rowCount, table.columnCount, ref, scope) { table.commonStyle(ref, scope) }
    val mixed = remember(table.styles, table.rowCount, table.columnCount, ref, scope) { table.mixedKinds(ref, scope) }
    val enabled = viewModel.isReady && !viewModel.isClosing && !viewModel.accessDenied && !viewModel.previewMode
    fun label(value: TableFormatScope) = when (value) {
        TableFormatScope.CELL -> uiText("Ячейка %1\$s:%2\$s", ref.row + 1, ref.column + 1)
        TableFormatScope.ROW -> uiText("Строка %1\$s", ref.row + 1)
        TableFormatScope.COLUMN -> uiText("Столбец %1\$s", ref.column + 1)
        TableFormatScope.TABLE -> uiText("Вся таблица")
    }
    fun apply(kind: FormatKind, value: String?) { viewModel.formatTableCells(ref, scope, kind, value) }
    val actions = FormattingActions(
        toggle = { kind -> apply(kind, if (active[kind] == "1") "0" else "1") },
        format = ::apply, heading = {}, list = {}, align = { apply(FormatKind.ALIGN, it) },
        indent = { apply(FormatKind.INDENT, ((active[FormatKind.INDENT]?.toIntOrNull() ?: 0) + it).coerceIn(0, 4).toString()) },
        selectAll = {})
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) {
            // The visible label already reads the scope; a duplicate stateDescription made TalkBack repeat it.
            TextButton(onClick = { chooseScope = true }, enabled = enabled,
                modifier = Modifier.testTag("table-format-scope")) {
                Text(label(scope), Modifier.weight(1f), maxLines = 2)
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(chooseScope, { chooseScope = false }) {
                TableFormatScope.entries.forEach { target -> DropdownMenuItem(text = { Text(label(target)) },
                    modifier = Modifier.semantics { selected = target == scope },
                    trailingIcon = { if (scope == target) Icon(Icons.Default.Check, null) },
                    onClick = { chooseScope = false; scope = target }) }
            }
        }
        Box {
            IconButton(onClick = { popup = FormatPopup(FormatAnchor.TOP, FormatMenu.FULL) }, enabled = enabled,
                modifier = Modifier.testTag("table-format-open").semantics { contentDescription = uiText("Оформление текста") }) { Text("Aa") }
            FormattingPopup(popup, FormatAnchor.TOP, { popup = it }, active, true, null, actions,
                cellMode = true, mixedKinds = mixed, onReset = { viewModel.clearTableStyles(ref, scope) })
        }
        IconButton(onClick = onGeometry, enabled = enabled, modifier = Modifier.testTag("table-geometry-toggle")) {
            Icon(Icons.Default.AspectRatio, uiText("Размеры ячеек"))
        }
    }
}
