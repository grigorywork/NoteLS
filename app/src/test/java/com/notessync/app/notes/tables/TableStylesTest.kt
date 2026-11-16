package com.notessync.app.notes.tables

import com.notessync.app.notes.text.FormatKind
import com.notessync.app.notes.history.*
import com.notessync.app.notes.text.TextFormatting
import org.junit.Assert.*
import org.junit.Test

class TableStylesTest {
    private val ref = TableCellRef("t", 0, 1)
    private fun table() = NoteTables().add("t", 2, 3).editCell(ref, "Привет 😀\nТекст")
    @Test fun `legacy encoding remains version one and styles upgrade only when used`() {
        val old = table(); assertEquals(1, old.version); assertFalse(old.encode().contains("styles"))
        val styled = old.formatCells(ref, TableFormatScope.CELL, FormatKind.FONT, "MONO")
        assertEquals(2, styled.version); assertEquals(styled, NoteTables.decode(styled.encode()))
        assertEquals(old, styled.clearStyles(ref, TableFormatScope.CELL))
        assertEquals(old.blocks[0].cells, styled.blocks[0].cells)
    }
    @Test fun `cell row column and table scopes affect exactly their targets`() {
        val expected = mapOf(TableFormatScope.CELL to 1, TableFormatScope.ROW to 3, TableFormatScope.COLUMN to 2, TableFormatScope.TABLE to 6)
        for ((scope, count) in expected) {
            val old = table(); val next = old.formatCells(ref, scope, FormatKind.SIZE, "32")
            assertEquals(count, next.blocks[0].styles.size)
            assertEquals(old.blocks[0].cells, next.blocks[0].cells)
            assertEquals(old.blocks[0].columnWidths, next.blocks[0].columnWidths)
            assertEquals(old.blocks[0].rowHeights, next.blocks[0].rowHeights)
            assertEquals(next, NoteTables.decode(next.encode()))
        }
    }
    @Test fun `mixed scopes never claim default and changing one property preserves the others`() {
        val next = table().formatCells(ref, TableFormatScope.ROW, FormatKind.BOLD, "1")
            .formatCells(ref, TableFormatScope.CELL, FormatKind.COLOR, "FFE53935")
        val t = next.blocks[0]
        assertEquals(mapOf(FormatKind.BOLD to "1"), t.commonStyle(ref, TableFormatScope.ROW))
        assertEquals(setOf(FormatKind.COLOR), t.mixedKinds(ref, TableFormatScope.ROW))
        val cleared = next.formatCells(ref, TableFormatScope.ROW, FormatKind.COLOR, null)
        assertTrue(cleared.blocks[0].mixedKinds(ref, TableFormatScope.ROW).isEmpty())
        assertEquals("1", cleared.blocks[0].styleAt(0, 1)[FormatKind.BOLD])
    }
    @Test fun `empty cells retain styles while text geometry and Unicode change`() {
        val styled = table().formatCells(ref, TableFormatScope.CELL, FormatKind.HIGHLIGHT, "FFFFD54F")
        val edited = styled.editCell(ref, "").resizeRow("t", 0, 120).resizeColumn("t", 1, 250).editCell(ref, "😀 新内容")
        assertEquals(styled.blocks[0].styles, edited.blocks[0].styles)
        assertEquals(edited, NoteTables.decode(edited.encode()))
    }
    @Test fun `all valid note formatting kinds survive table storage and individual resets`() {
        val values = listOf(FormatKind.BOLD to "1", FormatKind.ITALIC to "1", FormatKind.UNDERLINE to "1", FormatKind.STRIKE to "1",
            FormatKind.SIZE to "48", FormatKind.FONT to "MONTSERRAT", FormatKind.COLOR to "FF123456", FormatKind.HIGHLIGHT to "FFFFFFFF",
            FormatKind.ALIGN to "JUSTIFY", FormatKind.INDENT to "4")
        var next = table()
        values.forEach { (k,v) -> next = next.formatCells(ref, TableFormatScope.CELL, k,v) }
        assertEquals(values.toMap(), NoteTables.decode(next.encode())!!.blocks[0].styleAt(0,1))
        assertTrue(next.estimatedBytes > table().estimatedBytes)
        values.forEach { (k,_) -> next = next.formatCells(ref, TableFormatScope.CELL,k,null) }
        assertEquals(table(),next)
    }
    @Test fun `invalid values stale cells and removed styled tables do not damage other data`() {
        val old = table()
        for ((kind, value) in listOf(FormatKind.SIZE to "49",FormatKind.FONT to "NETWORK",FormatKind.COLOR to "00112233",FormatKind.ALIGN to "INVALID"))
            assertEquals(old,old.formatCells(ref,TableFormatScope.TABLE,kind,value))
        assertEquals(old,old.formatCells(ref.copy(row = 99),TableFormatScope.TABLE,FormatKind.BOLD,"1"))
        assertEquals(old,old.clearStyles(ref.copy(tableId = "gone"),TableFormatScope.TABLE))
        val other = old.add("other",1,1).formatCells(ref,TableFormatScope.TABLE,FormatKind.BOLD,"1")
        assertEquals(old.add("other",1,1).remove("t"), other.remove("t"))
    }
    @Test fun `malformed duplicate out of bounds and future styles fail closed without truncation`() {
        val good = table().formatCells(ref,TableFormatScope.CELL,FormatKind.BOLD,"1").encode()
        val rawStyle = "{\"row\":0,\"column\":1,\"values\":{\"BOLD\":\"1\"}}"
        for (bad in listOf(good.replace("\"version\":2","\"version\":99"),
            good.replace("\"BOLD\":\"1\"","\"FUTURE\":\"1\""), good.replace("\"BOLD\":\"1\"","\"SIZE\":\"999\""),
            good.replace("\"row\":0","\"row\":99"),good.replace(rawStyle,"$rawStyle,$rawStyle"),
            good.replace("\"version\":2","\"version\":1"))) assertNull(bad,NoteTables.decode(bad))
        assertNull(NoteTables.decode("{\"version\":2,\"blocks\":[]}"))
        assertEquals(good,NoteTables.decode(good)!!.encode())
    }
    @Test fun `style snapshots count towards the shared history memory budget`() {
        val old=table();val next=old.formatCells(ref,TableFormatScope.TABLE,FormatKind.FONT,"MONTSERRAT")
        val cursor=EditorCursor(field=EditorField.TABLE,cell=ref)
        val a=EditorSnapshot("","",TextFormatting(),cursor,old);val b=a.copy(tables=next)
        val h=EditorHistory(maxBytes=100_000)
        h.record(a,b,EditKind.ATOMIC);assertEquals(a,h.undo(b));assertEquals(b,h.redo(a))
        assertTrue(h.estimatedBytes>=old.estimatedBytes+next.estimatedBytes)
        val bounded=EditorHistory(maxBytes=1000)
        assertEquals(HistoryResult.TOO_LARGE,bounded.record(a,b,EditKind.ATOMIC));assertFalse(bounded.canUndo)
    }
}
