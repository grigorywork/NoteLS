package com.notessync.app.ui.home

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.ExperimentalFoundationApi
import com.notessync.app.security.biometrics.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.EventBusy
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NoteSort

@Composable
internal fun SortMenu(current: NoteSort, onSelect: (NoteSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = uiText("Сортировка"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(uiText("По дате") + if (current == NoteSort.BY_DATE) " ✓" else "") },
                leadingIcon = { Icon(Icons.Default.Update, null) },
                onClick = { onSelect(NoteSort.BY_DATE); open = false }
            )
            DropdownMenuItem(text = { Text(uiText("По сроку") + if (current == NoteSort.BY_DUE) " ✓" else "") },
                leadingIcon = { Icon(Icons.Default.EventBusy, null) },
                onClick = { onSelect(NoteSort.BY_DUE); open = false })
            DropdownMenuItem(
                text = { Text(uiText("По имени") + if (current == NoteSort.BY_NAME) " ✓" else "") },
                leadingIcon = { Icon(Icons.Default.SortByAlpha, null) },
                onClick = { onSelect(NoteSort.BY_NAME); open = false }
            )
        }
    }
}


internal fun filterTitle(filter: NoteFilter): String = when (filter) {
    NoteFilter.Trash -> uiText("Корзина")
    NoteFilter.All -> uiText("Заметки")
    NoteFilter.Tasks -> uiText("Задачи")
    NoteFilter.Completed -> uiText("Выполненные задачи")
    NoteFilter.Unfinished -> uiText("Невыполненные")
    NoteFilter.Overdue -> uiText("Просроченные")
    NoteFilter.Favorites -> uiText("Избранное")
    is NoteFilter.Notebook -> filter.name
    is NoteFilter.Tag -> if (filter.name == com.notessync.app.notes.diary.DiaryEntries.TAG_NAME) uiText("Дневник") else "#" + filter.name
}
