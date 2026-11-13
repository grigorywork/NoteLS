package com.notessync.app.ai

import com.notessync.app.data.local.model.TransformAccess
import com.notessync.app.notes.text.TextFormatting

data class EditorTransformSource(val access: TransformAccess, val body: String, val formatting: TextFormatting,
    val start: Int, val end: Int) {
    val text: String get() = body.substring(start, end)
}
