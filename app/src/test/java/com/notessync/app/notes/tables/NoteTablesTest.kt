package com.notessync.app.notes.tables

import com.notessync.app.notes.history.*
import com.notessync.app.notes.text.TextFormatting
import org.junit.Assert.*
import org.junit.Test

class NoteTablesTest {
    @Test fun `cells Unicode geometry and empty document survive serialization`() {
        val tables = NoteTables().add("one", 3, 2).editCell(TableCellRef("one", 2, 1), "Привет 😀\nновая строка")
            .resizeColumn("one", 1, 240).resizeRow("one", 2, 160)
        assertEquals(tables, NoteTables.decode(tables.encode()))
        assertEquals(NoteTables(), NoteTables.decode(""))
        assertEquals("", tables.remove("one").encode())
    }
    @Test fun `dimensions counts IDs cell and document text are bounded without truncation`() {
        fun rejects(block: () -> Unit) {
            try { block(); fail("expected bounded rejection") } catch (_: TableLimitReached) { }
        }
        rejects { NoteTables().add("t", 0, 1) }; rejects { NoteTables().add("t", 21, 1) }
        rejects { NoteTables().add("t", 1, 11) }; rejects { NoteTables().add("!", 1, 1) }
        val full = NoteTables().add("a", 20, 10).add("b", 20, 10)
        rejects { full.add("c", 1, 1) }; rejects { NoteTables().add("a", 1, 1).add("a", 1, 1) }
        var many = NoteTables(); repeat(8) { many = many.add("t$it", 1, 1) }
        rejects { many.add("ninth", 1, 1) }
        rejects { full.editCell(TableCellRef("a", 0, 0), "x".repeat(4097)) }
        var text = NoteTables().add("text", 20, 10)
        repeat(24) { text = text.editCell(TableCellRef("text", it / 10, it % 10), "x".repeat(4096)) }
        val kept = text
        rejects { text.editCell(TableCellRef("text", 2, 4), "x".repeat(4096)) }
        assertEquals(kept, text)
    }
    @Test fun `geometry clamps while invalid or stale cell addresses leave data intact`() {
        val table = NoteTables().add("one", 2, 2)
        val resized = table.resizeColumn("one", 0, Int.MAX_VALUE).resizeRow("one", 1, Int.MIN_VALUE)
        assertEquals(NoteTables.MAX_WIDTH, resized.blocks[0].columnWidths[0])
        assertEquals(NoteTables.MIN_HEIGHT, resized.blocks[0].rowHeights[1])
        assertEquals(table, table.editCell(TableCellRef("gone", 0, 0), "ignored"))
        assertEquals(table, table.editCell(TableCellRef("one", -1, 5), "ignored"))
        assertEquals(table, table.resizeRow("one", 2, 100))
        assertEquals(table, table.resizeColumn("gone", 0, 100))
    }
    @Test fun `future malformed nonrectangular and oversized data are not silently normalized`() {
        for (raw in listOf("garbage", "{\"version\":2,\"blocks\":[]}", "{\"future\":true}", "x".repeat(1_000_001),
            "{\"blocks\":[{\"id\":\"t\",\"cells\":[[\"a\"]],\"columnWidths\":[100,100],\"rowHeights\":[80]}]}")) {
            assertNull(raw.take(30), NoteTables.decode(raw))
        }
    }
    @Test fun `table input groups and text undo preserve current tables without extra table snapshots`() {
        val h = EditorHistory(maxBytes = 4096)
        val cursor = EditorCursor(field = EditorField.TABLE, cell = TableCellRef("t", 0, 0))
        val empty = EditorSnapshot("", "body", TextFormatting(), cursor, NoteTables().add("t", 1, 1))
        val a = empty.copy(tables = empty.tables.editCell(cursor.cell!!, "a"))
        val ab = a.copy(tables = a.tables.editCell(cursor.cell!!, "ab"))
        h.record(empty, a, EditKind.TABLE_INPUT); h.record(a, ab, EditKind.TABLE_INPUT)
        assertEquals(1, h.stepCount); assertEquals(empty, h.undo(ab)); assertEquals(ab, h.redo(empty))
        h.breakGroup()
        val text = ab.copy(content = "body!", cursor = ab.cursor.copy(field = EditorField.CONTENT))
        h.record(ab, text, EditKind.ATOMIC)
        assertEquals(ab, h.undo(text)); assertEquals(empty, h.undo(ab))
        assertTrue(h.estimatedBytes <= 4096)
    }
    @Test fun `resize remove and undo share memory budget with table text`() {
        val h = EditorHistory(maxSteps = 2, maxBytes = 20_000)
        val a = EditorSnapshot("", "", TextFormatting(), EditorCursor(), NoteTables().add("t", 2, 2))
        val b = a.copy(tables = a.tables.resizeColumn("t", 1, 300))
        val c = b.copy(tables = b.tables.editCell(TableCellRef("t", 0, 1), "cell"))
        val d = c.copy(tables = c.tables.remove("t"))
        h.record(a, b, EditKind.ATOMIC); h.record(b, c, EditKind.ATOMIC); h.record(c, d, EditKind.ATOMIC)
        assertEquals(c, h.undo(d)); assertEquals(b, h.undo(c)); assertFalse(h.canUndo)
        assertEquals(c, h.redo(b)); assertEquals(d, h.redo(c))
    }
}
