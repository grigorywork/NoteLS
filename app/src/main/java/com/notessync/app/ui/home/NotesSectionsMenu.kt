package com.notessync.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.notessync.app.i18n.uiText
import com.notessync.app.notes.diary.DiaryEntries
import com.notessync.app.ui.notes.NoteFilter

private data class NoteSection(val filter: NoteFilter, val label: String, val icon: ImageVector)
private val noteSections = listOf(
    NoteSection(NoteFilter.Unfinished, "Невыполненные", Icons.Default.CheckBoxOutlineBlank),
    NoteSection(NoteFilter.Overdue, "Просроченные", Icons.Default.EventBusy),
    NoteSection(NoteFilter.Completed, "Выполненные задачи", Icons.Default.DoneAll),
    NoteSection(NoteFilter.Trash, "Корзина", Icons.Default.Delete)
)

/** Secondary destinations occupy one overflow button rather than permanent drawer rows. */
@Composable
internal fun NotesSectionsMenu(filter: NoteFilter, onSelect: (NoteFilter) -> Unit, onOpenAgenda: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics {
            selected = noteSections.any { it.filter == filter } || filter == NoteFilter.Tag(DiaryEntries.TAG_NAME)
        }) { Icon(Icons.Default.MoreVert, uiText("Разделы заметок")) }
        DropdownMenu(open, { open = false }) {
            NoteSectionItems(filter, onSelect, onOpenAgenda) { open = false }
        }
    }
}

@Composable
internal fun NoteSectionItems(filter: NoteFilter, onSelect: (NoteFilter) -> Unit, onOpenAgenda: () -> Unit, close: () -> Unit) {
    noteSections.forEach { section ->
        DropdownMenuItem(text = { Text(uiText(section.label)) }, leadingIcon = { Icon(section.icon, null) },
            trailingIcon = { if (filter == section.filter) Icon(Icons.Default.Check, null) },
            onClick = { close(); onSelect(section.filter) })
    }
    HorizontalDivider()
    DropdownMenuItem(text = { Text(uiText("Дневник")) }, leadingIcon = { Icon(Icons.Default.Book, null) },
        trailingIcon = { if (filter == NoteFilter.Tag(DiaryEntries.TAG_NAME)) Icon(Icons.Default.Check, null) },
        onClick = { close(); onSelect(NoteFilter.Tag(DiaryEntries.TAG_NAME)) })
    DropdownMenuItem(text = { Text(uiText("Ежедневник")) }, leadingIcon = { Icon(Icons.Default.Today, null) },
        onClick = { close(); onOpenAgenda() })
}
