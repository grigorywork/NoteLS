package com.notessync.app.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DrawerState
import com.notessync.app.i18n.uiText
import com.notessync.app.security.biometrics.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.data.local.entity.NotebookEntity
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.EventBusy
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NotesViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.notessync.app.settings.SettingsState
import com.notessync.app.security.ProtectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesDrawer(viewModel: NotesViewModel, notebookUi: HomeNotebookState, filter: NoteFilter,
    notebooks: List<NotebookEntity>, protectionState: ProtectionState, biometricBindings: Map<String, String>,
    displaySettings: SettingsState, drawerQuoteIndex: Int, drawerState: DrawerState, scope: CoroutineScope,
    snackbarHostState: SnackbarHostState, settingsContext: android.content.Context,
    onOpenCalendar: () -> Unit, onOpenAnalytics: () -> Unit, onOpenQuotes: () -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Text(
            uiText("Заметки"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        NavigationDrawerItem(
            label = { Text(uiText("Все заметки")) },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            selected = filter == NoteFilter.All,
            onClick = {
                viewModel.selectFilter(NoteFilter.All)
                scope.launch { drawerState.close() }
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text(uiText("Задачи")) },
            icon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
            selected = filter == NoteFilter.Tasks,
            onClick = {
                viewModel.selectFilter(NoteFilter.Tasks)
                scope.launch { drawerState.close() }
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(label = { Text(uiText("Невыполненные")) }, icon = { Icon(Icons.Default.CheckBoxOutlineBlank, null) },
            selected = filter == NoteFilter.Unfinished, onClick = { viewModel.selectFilter(NoteFilter.Unfinished); scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
        NavigationDrawerItem(label = { Text(uiText("Просроченные")) }, icon = { Icon(Icons.Default.EventBusy, null) },
            selected = filter == NoteFilter.Overdue, onClick = { viewModel.selectFilter(NoteFilter.Overdue); scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
        NavigationDrawerItem(
            label = { Text(uiText("Избранное")) },
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            selected = filter == NoteFilter.Favorites,
            onClick = {
                viewModel.selectFilter(NoteFilter.Favorites)
                scope.launch { drawerState.close() }
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text(uiText("Корзина")) }, icon = { Icon(Icons.Default.Delete, null) },
            selected = filter == NoteFilter.Trash,
            onClick = { viewModel.selectFilter(NoteFilter.Trash); scope.launch { drawerState.close() } },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            label = { Text(uiText("Календарь")) },
            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            selected = false,
            onClick = {
                scope.launch { drawerState.close() }
                onOpenCalendar()
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // Этап 31: крупный отдельный пункт — аналитика выполненных задач.
        NavigationDrawerItem(
            label = { Text(uiText("Аналитика"), style = MaterialTheme.typography.titleMedium) },
            icon = {
                Icon(
                    Icons.Default.QueryStats,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            selected = false,
            onClick = {
                scope.launch { drawerState.close() }
                onOpenAnalytics()
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        // Этап 38: библиотека цитат мыслителей — в боковом меню рядом с аналитикой.
        NavigationDrawerItem(
            label = { Text(uiText("Цитаты"), style = MaterialTheme.typography.titleMedium) },
            icon = {
                Icon(
                    Icons.Default.FormatQuote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            selected = false,
            onClick = {
                scope.launch { drawerState.close() }
                onOpenQuotes()
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                uiText("Блокноты"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { notebookUi.showCreateNotebook = true }) {
                Icon(Icons.Default.Add, contentDescription = uiText("Новый блокнот"))
            }
        }
        notebooks.forEach { notebook ->
            var menuOpen by remember(notebook.id) { mutableStateOf(false) }
            val biometricTag = remember(notebook) { NotebookBiometricBinding.from(notebook)?.credentialTag }
            val biometricEnrolled = biometricTag != null && biometricBindings[notebook.syncId] == biometricTag
            val unlocked = !notebook.isProtected || notebook.id in protectionState.notebookIds
            NavigationDrawerItem(
                label = { Text(notebook.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = {
                    Icon(
                        if (notebook.isProtected && !unlocked) Icons.Default.Lock
                        else Icons.Default.LockOpen,
                        contentDescription = null
                    )
                },
                selected = (filter as? NoteFilter.Notebook)?.id == notebook.id,
                onClick = {
                    if (unlocked) {
                        viewModel.selectFilter(NoteFilter.Notebook(notebook.id, notebook.name))
                        scope.launch { drawerState.close() }
                    } else {
                        notebookUi.notebookUnlockError = false
                        notebookUi.notebookToUnlock = notebook
                    }
                },
                badge = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = uiText("Действия с блокнотом"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!notebook.isProtected) {
                                DropdownMenuItem(
                                    text = { Text(uiText("Защитить паролем")) },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        notebookUi.notebookToProtect = notebook
                                    }
                                )
                            } else {
                                if (unlocked) {
                                    DropdownMenuItem(
                                        text = { Text(uiText("Заблокировать")) },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.lockNotebook(notebook)
                                        }
                                    )
                                }
                                if (unlocked) DropdownMenuItem(
                                    text = { Text(if (biometricEnrolled) uiText("Выключить биометрию") else uiText("Включить биометрию")) },
                                    onClick = {
                                        menuOpen = false
                                        if (biometricEnrolled) {
                                            viewModel.biometrics.revoke(notebook.syncId) { success -> scope.launch { snackbarHostState.showSnackbar(if (success) uiText("Биометрия выключена") else uiText("Не удалось выключить биометрию")) } }
                                        } else if (canUseNotebookBiometrics(settingsContext)) {
                                            notebookUi.biometricPasswordError = false; notebookUi.notebookToEnrollBiometric = notebook
                                        } else scope.launch { snackbarHostState.showSnackbar(uiText("Биометрия недоступна. Проверьте отпечаток в настройках телефона.")) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(uiText("Снять защиту")) },
                                    leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        notebookUi.notebookRemoveError = false
                                        notebookUi.notebookToUnprotect = notebook
                                    }
                                )
                            }
                            if (unlocked) DropdownMenuItem(
                                text = { Text(uiText("Переименовать")) },
                                leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    notebookUi.notebookToEdit = notebook
                                }
                            )
                            if (unlocked) DropdownMenuItem(
                                text = { Text(uiText("Удалить")) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    notebookUi.notebookToDelete = notebook
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        if (displaySettings.drawerQuote.enabled && drawerState.isOpen) {
            com.notessync.app.ui.quotes.DrawerQuoteCard(displaySettings.drawerQuote, drawerQuoteIndex)
        }
        }
    }
}
