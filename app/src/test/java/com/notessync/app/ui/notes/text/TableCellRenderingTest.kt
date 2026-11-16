package com.notessync.app.ui.notes.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.notessync.app.notes.text.FormatKind
import org.junit.Assert.*
import org.junit.Test

class TableCellRenderingTest {
    @Test fun `cell style uses note values for font size colors decoration and alignment`() {
        val result=tableCellTextStyle(TextStyle(fontSize=16.sp,lineHeight=24.sp),mapOf(FormatKind.SIZE to "48",FormatKind.FONT to "MONO",
            FormatKind.BOLD to "1",FormatKind.UNDERLINE to "1",FormatKind.ALIGN to "CENTER",FormatKind.COLOR to "FF123456",FormatKind.INDENT to "2"))
        assertEquals(48.sp,result.fontSize);assertEquals(FontFamily.Monospace,result.fontFamily)
        assertEquals(FontWeight.Bold,result.fontWeight);assertEquals(TextAlign.Center,result.textAlign)
        assertTrue(result.textDecoration!!.contains(TextDecoration.Underline));assertTrue(result.lineHeight.value>=48f)
        assertEquals(24.sp,result.textIndent!!.firstLine)
    }
    @Test fun `cell indent step stays readable in narrow cells`() {
        assertEquals(24.sp,tableCellTextStyle(TextStyle(),mapOf(FormatKind.INDENT to "2")).textIndent!!.firstLine)
        assertEquals(48.sp,tableCellTextStyle(TextStyle(),mapOf(FormatKind.INDENT to "4")).textIndent!!.firstLine)
    }
    @Test fun `inherited text flips to readable contrast on dark fill`() {
        val base=TextStyle(fontSize=16.sp,color=Color(0xFF202124))
        assertEquals(Color.White,tableCellTextStyle(base,mapOf(FormatKind.HIGHLIGHT to "FF000000")).color)
        assertEquals(Color(0xFF202124),tableCellTextStyle(base,mapOf(FormatKind.HIGHLIGHT to "FFFFFFFF")).color)
    }
    @Test fun `explicit colour is never overridden by contrast correction`() {
        val base=TextStyle(fontSize=16.sp,color=Color(0xFF202124))
        val result=tableCellTextStyle(base,mapOf(FormatKind.HIGHLIGHT to "FF000000",FormatKind.COLOR to "FF123456"))
        assertEquals(Color(0xFF123456),result.color)
    }
    @Test fun `theme styles inherit and explicit off clears inherited bold`() {
        val base=TextStyle(fontSize=20.sp,fontWeight=FontWeight.Bold)
        assertEquals(20.sp,tableCellTextStyle(base,emptyMap()).fontSize)
        assertEquals(FontWeight.Normal,tableCellTextStyle(base,mapOf(FormatKind.BOLD to "0")).fontWeight)
    }
}
