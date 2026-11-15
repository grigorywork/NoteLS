package com.notessync.app.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class SelectionMenuPositionTest {
    @Test fun `selection toolbar remains within narrow visible window at every edge`() {
        for (rect in listOf(Rect(0f,0f,5f,20f), Rect(300f,590f,320f,630f), Rect(60f,100f,200f,200f))) {
            val point = SelectionMenuPosition(rect, 8).calculatePosition(IntRect.Zero, IntSize(320,640), LayoutDirection.Ltr, IntSize(308,44))
            assertTrue(point.x >= 0 && point.x + 308 <= 320)
            assertTrue(point.y >= 0 && point.y + 44 <= 640)
        }
    }
    @Test fun `toolbar avoids selection when enough room above or below`() {
        val middle = SelectionMenuPosition(Rect(30f,100f,180f,140f), 8)
            .calculatePosition(IntRect.Zero, IntSize(320,640), LayoutDirection.Ltr, IntSize(220,44))
        assertTrue(middle.y + 44 < 100)
        val top = SelectionMenuPosition(Rect(0f,0f,80f,20f), 8)
            .calculatePosition(IntRect.Zero, IntSize(320,640), LayoutDirection.Ltr, IntSize(220,44))
        assertTrue(top.y > 20)
    }
}
