package com.notessync.app.ui.notes

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import com.notessync.app.data.local.model.*
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.ui.theme.LocalGlassTheme

@Composable
internal fun noteCardContainer(note: NoteListEntry): Color {
    val palette = taskColorOption(if (note.isTask) note.taskColor else note.noteColor)
    val glass = LocalGlassTheme.current
    return if (note.isTask && note.isCompleted) palette.completedContainer.copy(alpha = if (glass.enabled) .55f else 1f)
        else if (note.isTask || note.noteColor.isNotEmpty()) lerp(MaterialTheme.colorScheme.surfaceVariant, palette.accent, .08f)
        else MaterialTheme.colorScheme.surfaceVariant
}

/** Keep the selected hue, with readable text in both themes. No per-frame color calculation. */
@Composable
internal fun noteAccent(note: NoteListEntry): Color {
    val background = noteCardContainer(note).compositeOver(MaterialTheme.colorScheme.surface)
    val fallback = if (background.luminance() > .179f) Color(0xFF1D1B20) else Color.White
    val raw = if (note.isTask) taskColorOption(note.taskColor).accent
        else if (note.noteColor.isNotEmpty()) taskColorOption(note.noteColor).accent else fallback
    return remember(raw, background, fallback) {
        (0..20).asSequence().map { lerp(raw, fallback, it / 20f) }.firstOrNull { color ->
            val a = color.luminance(); val b = background.luminance()
            (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f
        } ?: fallback
    }
}

@Composable
internal fun NoteCardMenu(note: NoteCardEntry, onDelete: () -> Unit, onSelect: () -> Unit,
    onPin: () -> Unit, onRestore: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, uiText("Действия")) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(uiText("Выбрать")) }, leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                onClick = { open = false; onSelect() })
            if (!note.isDeleted) DropdownMenuItem(text = { Text(if (note.isPinned) uiText("Открепить") else uiText("Закрепить")) },
                leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { open = false; onPin() })
            if (note.isDeleted) DropdownMenuItem(text = { Text(uiText("Восстановить")) }, leadingIcon = { Icon(Icons.Default.RestoreFromTrash, null) },
                onClick = { open = false; onRestore() })
            DropdownMenuItem(text = { Text(if (note.isDeleted) uiText("Удалить навсегда") else uiText("Удалить")) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { open = false; onDelete() })
        }
    }
}
