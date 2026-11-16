package com.notessync.app.notes.tables

import com.notessync.app.notes.text.FormatKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TableLimitReached : IllegalArgumentException("Table limits exceeded")
data class TableCellRef(val tableId: String, val row: Int, val column: Int)

enum class TableFormatScope { CELL, ROW, COLUMN, TABLE }
@Serializable
data class TableCellStyle(val row: Int, val column: Int, val values: Map<String, String>)

@Serializable
data class NoteTable(
    val id: String,
    val cells: List<List<String>>,
    val columnWidths: List<Int>,
    val rowHeights: List<Int>,
    val styles: List<TableCellStyle> = emptyList()
) {
    fun styleAt(row: Int, column: Int): Map<FormatKind, String> =
        styles.firstOrNull { it.row == row && it.column == column }?.values.orEmpty()
            .mapKeys { FormatKind.valueOf(it.key) }
    fun targets(ref: TableCellRef, scope: TableFormatScope): List<Pair<Int, Int>> =
        if (ref.tableId != id || !contains(ref.row, ref.column)) emptyList() else buildList {
            cells.indices.forEach { row -> columnWidths.indices.forEach { column ->
                if (scope == TableFormatScope.TABLE || (row == ref.row && column == ref.column) ||
                    (scope == TableFormatScope.ROW && row == ref.row) ||
                    (scope == TableFormatScope.COLUMN && column == ref.column)) add(row to column)
            } }
        }
    fun commonStyle(ref: TableCellRef, scope: TableFormatScope): Map<FormatKind, String> {
        val styles = targets(ref, scope).map { (r, c) -> styleAt(r, c) }
        return styles.firstOrNull().orEmpty().filter { (kind, value) -> styles.all { it[kind] == value } }
    }
    fun mixedKinds(ref: TableCellRef, scope: TableFormatScope): Set<FormatKind> {
        val styles = targets(ref, scope).map { (r, c) -> styleAt(r, c) }
        return FormatKind.entries.filterTo(mutableSetOf()) { kind -> styles.map { it[kind] }.distinct().size > 1 }
    }
    val rowCount: Int get() = cells.size
    val columnCount: Int get() = columnWidths.size
    fun contains(row: Int, column: Int) = row in cells.indices && column in columnWidths.indices
}

/** Immutable, bounded table blocks; dimensions are density-independent pixels. No platform I/O. */
@Serializable
data class NoteTables(val version: Int = 1, val blocks: List<NoteTable> = emptyList()) {
    fun encode(): String {
        checked()
        if (blocks.isEmpty()) return ""
        return json.encodeToString(this).also { if (it.length > MAX_JSON) throw TableLimitReached() }
    }
    fun find(id: String): NoteTable? = blocks.firstOrNull { it.id == id }
    val cellCount: Int get() = blocks.sumOf { it.rowCount * it.columnCount }
    val estimatedBytes: Long get() = 128L + blocks.sumOf { table ->
        192L + 2L * table.id.length + 32L * (table.rowCount + table.columnCount) +
            table.cells.sumOf { row -> 32L + row.sumOf { 48L + 2L * it.length } } +
            table.styles.sumOf { style -> 128L + style.values.entries.sumOf { 64L + 2L * (it.key.length + it.value.length) } }
    }

    fun add(id: String, rows: Int, columns: Int): NoteTables {
        if (rows !in 1..MAX_ROWS || columns !in 1..MAX_COLUMNS) throw TableLimitReached()
        return copy(blocks = blocks + NoteTable(id, List(rows) { List(columns) { "" } },
            List(columns) { 160 }, List(rows) { 80 })).checked()
    }
    fun remove(id: String) = canonical(blocks.filterNot { it.id == id })
    fun formatCells(ref: TableCellRef, scope: TableFormatScope, kind: FormatKind, value: String?): NoteTables {
        if (value != null && !kind.valid(value)) return this
        return changeStyles(ref, scope) { values -> if (value == null) values - kind.name else values + (kind.name to value) }
    }
    fun clearStyles(ref: TableCellRef, scope: TableFormatScope): NoteTables = changeStyles(ref, scope) { emptyMap() }
    private fun changeStyles(ref: TableCellRef, scope: TableFormatScope,
        change: (Map<String, String>) -> Map<String, String>): NoteTables {
        val table = find(ref.tableId) ?: return this
        val targets = table.targets(ref, scope)
        if (targets.isEmpty()) return this
        val values = table.styles.associate { (it.row to it.column) to it.values }.toMutableMap()
        targets.forEach { cell ->
            val next = change(values[cell].orEmpty())
            if (next.isEmpty()) values.remove(cell) else values[cell] = next
        }
        val styles = values.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (cell, value) -> TableCellStyle(cell.first, cell.second, value.toSortedMap()) }
        return canonical(blocks.map { if (it.id == table.id) it.copy(styles = styles) else it }).checked()
    }
    private fun canonical(tables: List<NoteTable>) = copy(
        version = if (tables.any { it.styles.isNotEmpty() }) 2 else 1, blocks = tables)
    fun editCell(ref: TableCellRef, text: String): NoteTables {
        val table = find(ref.tableId) ?: return this
        if (!table.contains(ref.row, ref.column) || table.cells[ref.row][ref.column] == text) return this
        if (text.length > MAX_CELL_TEXT) throw TableLimitReached()
        return replace(table.copy(cells = table.cells.mapIndexed { r, row ->
            if (r == ref.row) row.mapIndexed { c, value -> if (c == ref.column) text else value } else row
        })).checked()
    }
    fun resizeColumn(id: String, column: Int, width: Int): NoteTables {
        val table = find(id) ?: return this
        if (column !in table.columnWidths.indices) return this
        return replace(table.copy(columnWidths = table.columnWidths.mapIndexed { i, value ->
            if (i == column) width.coerceIn(MIN_WIDTH, MAX_WIDTH) else value
        }))
    }
    fun resizeRow(id: String, row: Int, height: Int): NoteTables {
        val table = find(id) ?: return this
        if (row !in table.rowHeights.indices) return this
        return replace(table.copy(rowHeights = table.rowHeights.mapIndexed { i, value ->
            if (i == row) height.coerceIn(MIN_HEIGHT, MAX_HEIGHT) else value
        }))
    }
    private fun replace(table: NoteTable) = copy(blocks = blocks.map { if (it.id == table.id) table else it })
    private fun checked(): NoteTables {
        if (version !in 1..2 || (version == 1 && blocks.any { it.styles.isNotEmpty() }) ||
            (version == 2 && blocks.none { it.styles.isNotEmpty() }) || blocks.size > MAX_TABLES || blocks.map { it.id }.distinct().size != blocks.size ||
            blocks.any { table ->
                !ID.matches(table.id) || table.rowCount !in 1..MAX_ROWS || table.columnCount !in 1..MAX_COLUMNS ||
                    table.rowHeights.size != table.rowCount || table.cells.any { it.size != table.columnCount } ||
                    table.columnWidths.any { it !in MIN_WIDTH..MAX_WIDTH } ||
                    table.rowHeights.any { it !in MIN_HEIGHT..MAX_HEIGHT } ||
                    table.cells.any { row -> row.any { it.length > MAX_CELL_TEXT } } ||
                    table.styles.size > table.rowCount * table.columnCount ||
                    table.styles.map { it.row to it.column }.distinct().size != table.styles.size ||
                    table.styles.any { style -> !table.contains(style.row, style.column) || style.values.isEmpty() ||
                        style.values.size > FormatKind.entries.size || style.values.any { (kind, value) ->
                            FormatKind.entries.firstOrNull { it.name == kind }?.valid(value) != true
                        } }
            } || cellCount > MAX_CELLS || blocks.sumOf { t -> t.cells.sumOf { row -> row.sumOf { it.length } } } > MAX_TEXT
        ) throw TableLimitReached()
        return this
    }

    companion object {
        const val MAX_ROWS = 20
        const val MAX_COLUMNS = 10
        const val MAX_TABLES = 8
        const val MAX_CELLS = 400
        const val MAX_CELL_TEXT = 4096
        const val MAX_TEXT = 100_000
        const val MIN_WIDTH = 64
        const val MAX_WIDTH = 600
        const val MIN_HEIGHT = 64
        const val MAX_HEIGHT = 400
        private const val MAX_JSON = 1_000_000
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val json = Json { ignoreUnknownKeys = false }

        /** Null means unsupported/corrupt data: callers must preserve the original bytes on save. */
        fun decode(raw: String): NoteTables? {
            if (raw.isEmpty()) return NoteTables()
            if (raw.length > MAX_JSON) return null
            return runCatching { json.decodeFromString<NoteTables>(raw).checked() }.getOrNull()
        }
    }
}
