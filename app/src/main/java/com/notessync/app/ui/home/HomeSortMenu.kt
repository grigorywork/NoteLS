package com.notessync.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.notessync.app.i18n.uiText
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NoteSort

@Composable
internal fun HomeActionsMenu(current: NoteSort, onSelect: (NoteSort) -> Unit, filter: NoteFilter,
    onFilter: (NoteFilter) -> Unit, onSettings: () -> Unit, onAgenda: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { sorting = false; open = true }) {
            Icon(Icons.Default.MoreVert, uiText("Действия со списком"))
        }
        DropdownMenu(open, { open = false }) {
            if (sorting) {
                listOf(Triple(NoteSort.BY_DATE, "По дате", Icons.Default.Update),
                    Triple(NoteSort.BY_DUE, "По сроку", Icons.Default.EventBusy),
                    Triple(NoteSort.BY_NAME, "По имени", Icons.Default.SortByAlpha)).forEach { (sort, label, icon) ->
                    DropdownMenuItem(text = { Text(uiText(label)) }, leadingIcon = { Icon(icon, null) },
                        modifier = Modifier.semantics { selected = current == sort },
                        trailingIcon = { if (current == sort) Icon(Icons.Default.Check, null) },
                        onClick = { onSelect(sort); open = false })
                }
            } else {
                DropdownMenuItem(text = { Text(uiText("Сортировка")) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                    onClick = { sorting = true })
                DropdownMenuItem(text = { Text(uiText("Настройки")) }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                    onClick = { open = false; onSettings() })
                HorizontalDivider()
                NoteSectionItems(filter, onFilter, onAgenda) { open = false }
            }
        }
    }
}

internal fun filterTitle(filter: NoteFilter): String = when (filter) {
    NoteFilter.Trash -> uiText("Корзина")
    NoteFilter.All -> uiText("Заметки")
    NoteFilter.Tasks -> uiText("Задачи")
    NoteFilter.Completed -> uiText("Выполненные")
    NoteFilter.Unfinished -> uiText("Невыполненные")
    NoteFilter.Overdue -> uiText("Просроченные")
    NoteFilter.Favorites -> uiText("Избранное")
    is NoteFilter.Notebook -> filter.name
    is NoteFilter.Tag -> if (filter.name == com.notessync.app.notes.diary.DiaryEntries.TAG_NAME) uiText("Дневник") else "#" + filter.name
}
