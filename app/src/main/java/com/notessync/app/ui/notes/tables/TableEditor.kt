package com.notessync.app.ui.notes.tables

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import com.notessync.app.notes.history.EditorField
import com.notessync.app.notes.history.EditorSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notessync.app.notes.tables.*
import com.notessync.app.ui.notes.EditorScaffold
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.text.EditorHistoryActions
import com.notessync.app.ui.notes.text.HistoryInput
import kotlin.math.roundToInt

@Composable
fun AddTableDialog(onDismiss: () -> Unit, remainingCells: Int = NoteTables.MAX_CELLS,
    remainingTables: Int = NoteTables.MAX_TABLES, onCreate: (Int, Int) -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var columns by remember { mutableStateOf("3") }
    val r = rows.toIntOrNull(); val c = columns.toIntOrNull()
    AlertDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
        title = { Text(uiText("Новая таблица")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (remainingTables > 0) uiText("Доступно ячеек: %1\$s", (remainingCells)) else uiText("Достигнут предел: 8 таблиц"),
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(rows, { rows = it.filter(Char::isDigit).take(2) }, label = { Text(uiText("Строки: 1–20")) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = r !in 1..NoteTables.MAX_ROWS)
            OutlinedTextField(columns, { columns = it.filter(Char::isDigit).take(2) }, label = { Text(uiText("Столбцы: 1–10")) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = c !in 1..NoteTables.MAX_COLUMNS)
        }
    }, confirmButton = { TextButton(onClick = { onCreate(r!!, c!!) },
        enabled = r in 1..NoteTables.MAX_ROWS && c in 1..NoteTables.MAX_COLUMNS &&
            remainingTables > 0 && (r ?: 0) * (c ?: 0) <= remainingCells) { Text(uiText("Добавить")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Отмена")) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableEditorDialog(id: String, viewModel: NoteEditorViewModel, snackbarHostState: SnackbarHostState, onDismiss: () -> Unit) {
    val table = viewModel.tables?.find(id)
    if (table == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var delete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (!viewModel.historyEnabled || !it.isCtrlPressed || it.type != KeyEventType.KeyDown) false
            else when (it.key) {
                Key.Z -> { if (it.isShiftPressed) viewModel.redo() else viewModel.undo(); true }
                Key.Y -> { viewModel.redo(); true }
                Key.V, Key.X -> { viewModel.prepareClipboardEdit(); false }
                else -> false
            }
        }) {
            EditorScaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
                TopAppBar(title = { Text(uiText("Таблица %1\$s × %2\$s", (table.rowCount), (table.columnCount))) }, navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Закрыть таблицу")) }
                }, actions = {
                    IconButton(onClick = { delete = true }) { Icon(Icons.Default.Delete, uiText("Удалить таблицу")) }
                })
            }, bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    if (viewModel.historyEnabled) EditorHistoryActions(viewModel.canUndo, viewModel.canRedo, viewModel::undo, viewModel::redo)
                }
            }) {
                TableGrid(table, viewModel::onTableCellChange, viewModel::resizeTableColumn, viewModel::resizeTableRow,
                    onFocus = viewModel::focusTableCell, onClipboardEdit = viewModel::prepareClipboardEdit,
                    restoreVersion = viewModel.historyRestoreVersion,
                    restoreCell = viewModel.focusedTableCell.takeIf { viewModel.editorField == EditorField.TABLE },
                    restoreSelection = viewModel.focusedTableSelection)
            }
        }
        if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(uiText("Удалить таблицу?")) },
            text = { Text(uiText("Будут удалены все её ячейки.")) },
            confirmButton = { TextButton(onClick = { viewModel.removeTable(id); onDismiss() }) { Text(uiText("Удалить")) } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text(uiText("Отмена")) } })
    }
}

/** Only visible rows are composed. A shared scroll state aligns all columns without polling. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableGrid(
    table: NoteTable,
    onCell: (TableCellRef, TextFieldValue, TextFieldValue) -> Unit,
    onColumnWidth: (String, Int, Int) -> Unit,
    onRowHeight: (String, Int, Int) -> Unit,
    onFocus: (TableCellRef) -> Unit = {},
    onClipboardEdit: () -> Unit = {},
    restoreVersion: Long = 0,
    restoreCell: TableCellRef? = null,
    restoreSelection: EditorSelection? = null
) {
    val density = LocalDensity.current.density
    val horizontal = rememberScrollState()
    val vertical = rememberLazyListState()
    var selectedRow by remember(table.id) { mutableIntStateOf(0) }
    var selectedColumn by remember(table.id) { mutableIntStateOf(0) }
    var columnDrag by remember(table.id) { mutableStateOf<Pair<Int, Float>?>(null) }
    var rowDrag by remember(table.id) { mutableStateOf<Pair<Int, Float>?>(null) }
    val requesters = remember(table.id, table.rowCount, table.columnCount) {
        List(table.rowCount) { List(table.columnCount) { FocusRequester() } }
    }
    fun width(column: Int): Float = columnDrag?.takeIf { it.first == column }?.second ?: table.columnWidths[column].toFloat()
    fun height(row: Int): Float = rowDrag?.takeIf { it.first == row }?.second ?: table.rowHeights[row].toFloat()
    LaunchedEffect(restoreVersion) {
        val cell = restoreCell ?: return@LaunchedEffect
        if (restoreVersion > 0 && cell.tableId == table.id && table.contains(cell.row, cell.column)) {
            selectedRow = cell.row; selectedColumn = cell.column
            vertical.scrollToItem(cell.row)
            horizontal.scrollTo((table.columnWidths.take(cell.column).sum() * density).roundToInt())
            // Scroll can bring a previously uncomposed row into the next frame.
            withFrameNanos { }
            requesters[cell.row][cell.column].requestFocus()
        }
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(uiText("Столбец %1\$s", (selectedColumn + 1)), Modifier.align(Alignment.CenterVertically))
            IconButton(onClick = { onColumnWidth(table.id, selectedColumn, table.columnWidths[selectedColumn] - 16) },
                enabled = table.columnWidths[selectedColumn] > NoteTables.MIN_WIDTH) { Icon(Icons.Default.Remove, uiText("Уже")) }
            Text("${table.columnWidths[selectedColumn]}", Modifier.align(Alignment.CenterVertically))
            IconButton(onClick = { onColumnWidth(table.id, selectedColumn, table.columnWidths[selectedColumn] + 16) },
                enabled = table.columnWidths[selectedColumn] < NoteTables.MAX_WIDTH) { Icon(Icons.Default.Add, uiText("Шире")) }
            Text(uiText("Строка %1\$s", (selectedRow + 1)), Modifier.align(Alignment.CenterVertically))
            IconButton(onClick = { onRowHeight(table.id, selectedRow, table.rowHeights[selectedRow] - 16) },
                enabled = table.rowHeights[selectedRow] > NoteTables.MIN_HEIGHT) { Icon(Icons.Default.Remove, uiText("Ниже")) }
            Text("${table.rowHeights[selectedRow]}", Modifier.align(Alignment.CenterVertically))
            IconButton(onClick = { onRowHeight(table.id, selectedRow, table.rowHeights[selectedRow] + 16) },
                enabled = table.rowHeights[selectedRow] < NoteTables.MAX_HEIGHT) { Icon(Icons.Default.Add, uiText("Выше")) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(horizontal).testTag("table-columns")) {
            Spacer(Modifier.width(48.dp))
            table.columnWidths.indices.forEach { column ->
                Row(Modifier.width(width(column).dp).height(48.dp).border(0.5.dp, MaterialTheme.colorScheme.outline),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${column + 1}", Modifier.weight(1f).padding(start = 8.dp))
                    Box(Modifier.size(48.dp).testTag("column-handle-$column")
                        .semantics { contentDescription = uiText("Ширина столбца %1\$s", (column + 1)) }
                        .pointerInput(table.id, column, table.columnWidths[column], density) {
                            detectHorizontalDragGestures(onDragStart = { columnDrag = column to table.columnWidths[column].toFloat() },
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    columnDrag = column to ((columnDrag?.second ?: table.columnWidths[column].toFloat()) + amount / density)
                                        .coerceIn(NoteTables.MIN_WIDTH.toFloat(), NoteTables.MAX_WIDTH.toFloat())
                                }, onDragEnd = {
                                    columnDrag?.let { onColumnWidth(table.id, it.first, it.second.roundToInt()) }; columnDrag = null
                                }, onDragCancel = { columnDrag = null })
                        }, contentAlignment = Alignment.Center) { Icon(Icons.Default.DragIndicator, null) }
                }
            }
        }
        LazyColumn(state = vertical, modifier = Modifier.fillMaxWidth().weight(1f).testTag("table-rows")) {
            items(table.cells.indices.toList(), key = { it }) { row ->
                Row(Modifier.fillMaxWidth().horizontalScroll(horizontal)) {
                    Box(Modifier.width(48.dp).height(height(row).dp).testTag("row-handle-$row")
                        .semantics { contentDescription = uiText("Высота строки %1\$s", (row + 1)) }
                        .pointerInput(table.id, row, table.rowHeights[row], density) {
                            detectVerticalDragGestures(onDragStart = { rowDrag = row to table.rowHeights[row].toFloat() },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    rowDrag = row to ((rowDrag?.second ?: table.rowHeights[row].toFloat()) + amount / density)
                                        .coerceIn(NoteTables.MIN_HEIGHT.toFloat(), NoteTables.MAX_HEIGHT.toFloat())
                                }, onDragEnd = {
                                    rowDrag?.let { onRowHeight(table.id, it.first, it.second.roundToInt()) }; rowDrag = null
                                }, onDragCancel = { rowDrag = null })
                        }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${row + 1}"); Icon(Icons.Default.DragHandle, null)
                        }
                    }
                    table.columnWidths.indices.forEach { column ->
                        val ref = TableCellRef(table.id, row, column)
                        val savedText = table.cells[row][column]
                        var localValue by remember(table.id, row, column) { mutableStateOf(TextFieldValue(savedText, TextRange(savedText.length))) }
                        val displayed = if (localValue.text == savedText) localValue else TextFieldValue(savedText,
                            TextRange(localValue.selection.start.coerceIn(0, savedText.length), localValue.selection.end.coerceIn(0, savedText.length)))
                        LaunchedEffect(restoreVersion) {
                            if (restoreVersion > 0 && restoreCell == ref) {
                                val selection = restoreSelection?.bounded(savedText.length)
                                localValue = TextFieldValue(savedText, selection?.let { TextRange(it.start, it.end) } ?: TextRange(savedText.length))
                            }
                        }
                        HistoryInput(onClipboardEdit) {
                            BasicTextField(value = displayed, onValueChange = {
                                if (it.text.length <= NoteTables.MAX_CELL_TEXT) localValue = it
                                onCell(ref, displayed, it)
                            },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.width(width(column).dp).height(height(row).dp)
                                    .background(MaterialTheme.colorScheme.surface).border(0.5.dp, MaterialTheme.colorScheme.outline)
                                    .focusRequester(requesters[row][column]).onFocusChanged {
                                        if (it.isFocused) { selectedRow = row; selectedColumn = column; onFocus(ref) }
                                    }.padding(8.dp).testTag("cell-$row-$column")
                                    .semantics { contentDescription = uiText("Строка %1\$s, столбец %2\$s", (row + 1), (column + 1)) })
                        }
                    }
                }
            }
        }
    }
}
