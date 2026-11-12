package com.notessync.app.ui.notes.text

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EditorHistoryActions(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, uiText("Отменить")) }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, uiText("Повторить", context = "history")) }
    }
}
