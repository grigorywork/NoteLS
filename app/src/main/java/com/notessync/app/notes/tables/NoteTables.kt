package com.notessync.app.notes.tables

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TableLimitReached : IllegalArgumentException("Table limits exceeded")
data class TableCellRef(val tableId: String, val row: Int, val column: Int)

@Serializable
data class NoteTable(
    val id: String,
    val cells: List<List<String>>,
    val columnWidths: List<Int>,
    val rowHeights: List<Int>
) {
    val rowCount: Int get() = cells.size
    val columnCount: Int get() = columnWidths.size
    fun contains(row: Int, column: Int) = row in cells.indices && column in columnWidths.indices
}

/** Immutable, bounded table blocks; dimensions are density-independent pixels. No platform I/O. */
@Serializable
data class NoteTables(val version: Int = 1, val blocks: List<NoteTable> = emptyList()) {
    fun encode(): String { checked(); return if (blocks.isEmpty()) "" else json.encodeToString(this) }
    fun find(id: String): NoteTable? = blocks.firstOrNull { it.id == id }
    val cellCount: Int get() = blocks.sumOf { it.rowCount * it.columnCount }
    val estimatedBytes: Long get() = 128L + blocks.sumOf { table ->
        192L + 2L * table.id.length + 32L * (table.rowCount + table.columnCount) +
            table.cells.sumOf { row -> 32L + row.sumOf { 48L + 2L * it.length } }
    }

    fun add(id: String, rows: Int, columns: Int): NoteTables {
        if (rows !in 1..MAX_ROWS || columns !in 1..MAX_COLUMNS) throw TableLimitReached()
        return copy(blocks = blocks + NoteTable(id, List(rows) { List(columns) { "" } },
            List(columns) { 160 }, List(rows) { 80 })).checked()
    }
    fun remove(id: String) = copy(blocks = blocks.filterNot { it.id == id })
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
        if (version != 1 || blocks.size > MAX_TABLES || blocks.map { it.id }.distinct().size != blocks.size ||
            blocks.any { table ->
                !ID.matches(table.id) || table.rowCount !in 1..MAX_ROWS || table.columnCount !in 1..MAX_COLUMNS ||
                    table.rowHeights.size != table.rowCount || table.cells.any { it.size != table.columnCount } ||
                    table.columnWidths.any { it !in MIN_WIDTH..MAX_WIDTH } ||
                    table.rowHeights.any { it !in MIN_HEIGHT..MAX_HEIGHT } ||
                    table.cells.any { row -> row.any { it.length > MAX_CELL_TEXT } }
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
