package com.notessync.app.notes.tables

import androidx.compose.ui.text.input.TextFieldValue
import com.notessync.app.ui.notes.EditorDocument
import com.notessync.app.notes.text.FormatKind
import org.junit.Assert.*
import org.junit.Test

class TableStyleHistoryTest {
    @Test fun `whole table formatting is one undo step and cell text remains independent`() {
        val d=EditorDocument(true,{true},{})
        val id=d.addTable(2,2)!!; val ref=TableCellRef(id,1,1)
        d.editTableCell(ref,"cell")
        val before=d.tables
        d.formatTableCells(ref,TableFormatScope.TABLE,FormatKind.SIZE,"32")
        val styled=d.tables!!;assertEquals(4,styled.find(id)!!.styles.size)
        d.undo();assertEquals(before,d.tables)
        d.redo();assertEquals(styled,d.tables)
        d.editTableCell(ref,"new text");d.undo();assertEquals(styled,d.tables)
        assertEquals(ref,d.focusedTableCell)
    }
    @Test fun `reset is undoable and denied access blocks both style mutation paths`() {
        var editable=true
        val d=EditorDocument(true,{editable},{})
        val id=d.addTable(1,1)!!;val ref=TableCellRef(id,0,0)
        d.formatTableCells(ref,TableFormatScope.CELL,FormatKind.BOLD,"1")
        val styled=d.tables
        d.clearTableStyles(ref,TableFormatScope.TABLE);assertEquals(1,d.tables!!.version)
        d.undo();assertEquals(styled,d.tables)
        editable=false
        d.formatTableCells(ref,TableFormatScope.CELL,FormatKind.COLOR,"FFE53935");d.clearTableStyles(ref,TableFormatScope.TABLE)
        assertEquals(styled,d.tables)
    }
    @Test fun `note formatting and whole cell formatting never target one another`() {
        val d=EditorDocument(true,{true},{})
        d.contentField=TextFieldValue("Body")
        val id=d.addTable(1,1)!!;val ref=TableCellRef(id,0,0)
        d.formatTableCells(ref,TableFormatScope.CELL,FormatKind.FONT,"MONO")
        assertTrue(d.formatting.marks.isEmpty());assertEquals("Body",d.contentField.text)
        val styled=d.tables
        d.selectAllContent();d.formatSelection(FormatKind.BOLD,"1")
        assertEquals(styled,d.tables);assertFalse(d.formatting.marks.isEmpty())
    }
}
