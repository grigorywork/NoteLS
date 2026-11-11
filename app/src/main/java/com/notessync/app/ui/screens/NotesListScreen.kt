package com.notessync.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.ProtectionSession
import com.notessync.app.ui.common.SetPasswordDialog
import com.notessync.app.ui.common.UnlockPasswordDialog
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NoteSort
import com.notessync.app.ui.notes.NotesViewModel
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.tasks.TaskTimerIndicator
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.ui.theme.GlassPanelShape
import com.notessync.app.ui.theme.LocalGlassTheme
import com.notessync.app.ui.theme.glassBorder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotesListScreen(
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuotes: () -> Unit,
    onOpenEditor: (Long, Boolean) -> Unit,
    deletedNoteEvents: StateFlow<Long>? = null,
    onDeletedEventHandled: () -> Unit = {},
    viewModel: NotesViewModel = viewModel()
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val lockedTasks by viewModel.lockedTasks.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val protectionState by ProtectionSession.state.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var deleteCandidate by remember { mutableStateOf<NoteEntity?>(null) }
    var showCreateNotebook by remember { mutableStateOf(false) }
    var notebookToEdit by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToProtect by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToUnlock by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToUnprotect by remember { mutableStateOf<NotebookEntity?>(null) }
    var taskToUnlock by remember { mutableStateOf<NoteEntity?>(null) }
    var notebookUnlockError by remember { mutableStateOf(false) }
    var notebookRemoveError by remember { mutableStateOf(false) }
    var taskUnlockError by remember { mutableStateOf(false) }

    val notebookFilterId = (filter as? NoteFilter.Notebook)?.id
    val visibleLockedTasks = if (query.isBlank()) {
        lockedTasks.filter { task ->
            filter == NoteFilter.All || filter == NoteFilter.Tasks ||
                (notebookFilterId != null && task.notebookId == notebookFilterId)
        }
    } else {
        emptyList()
    }

    LaunchedEffect(deletedNoteEvents) {
        deletedNoteEvents?.collect { id ->
            if (id > 0) {
                onDeletedEventHandled()
                val result = snackbarHostState.showSnackbar("Заметка удалена", actionLabel = "Отменить")
                if (result == SnackbarResult.ActionPerformed) viewModel.restore(id)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Заметки",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Все заметки") },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    selected = filter == NoteFilter.All,
                    onClick = {
                        viewModel.selectFilter(NoteFilter.All)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Задачи") },
                    icon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                    selected = filter == NoteFilter.Tasks,
                    onClick = {
                        viewModel.selectFilter(NoteFilter.Tasks)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Избранное") },
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    selected = filter == NoteFilter.Favorites,
                    onClick = {
                        viewModel.selectFilter(NoteFilter.Favorites)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Календарь") },
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
                    label = { Text("Аналитика", style = MaterialTheme.typography.titleMedium) },
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
                    label = { Text("Цитаты", style = MaterialTheme.typography.titleMedium) },
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
                        "Блокноты",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showCreateNotebook = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Новый блокнот")
                    }
                }
                notebooks.forEach { notebook ->
                    var menuOpen by remember(notebook.id) { mutableStateOf(false) }
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
                                notebookUnlockError = false
                                notebookToUnlock = notebook
                            }
                        },
                        badge = {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Действия с блокнотом")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    if (!notebook.isProtected) {
                                        DropdownMenuItem(
                                            text = { Text("Защитить паролем") },
                                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                notebookToProtect = notebook
                                            }
                                        )
                                    } else {
                                        if (unlocked) {
                                            DropdownMenuItem(
                                                text = { Text("Заблокировать") },
                                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                                onClick = {
                                                    menuOpen = false
                                                    viewModel.lockNotebook(notebook)
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Снять защиту") },
                                            leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                notebookRemoveError = false
                                                notebookToUnprotect = notebook
                                            }
                                        )
                                    }
                                    if (unlocked) DropdownMenuItem(
                                        text = { Text("Переименовать") },
                                        leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            notebookToEdit = notebook
                                        }
                                    )
                                    if (unlocked) DropdownMenuItem(
                                        text = { Text("Удалить") },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            notebookToDelete = notebook
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(filterTitle(filter)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenAnalytics) {
                            Icon(Icons.Default.QueryStats, contentDescription = "Аналитика")
                        }
                        SortMenu(sort, viewModel::selectSort)
                        IconButton(onClick = onOpenSync) {
                            Icon(Icons.Default.Sync, contentDescription = "Синхронизация")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    }
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(
                        onClick = { onOpenEditor(-1L, true) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.CheckBox, contentDescription = "Новая задача")
                    }
                    FloatingActionButton(onClick = { onOpenEditor(-1L, false) }) {
                        Icon(Icons.Default.Add, contentDescription = "Новая заметка")
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                DockedSearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = query,
                            onQueryChange = viewModel::onQueryChange,
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text("Поиск") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                                    }
                                }
                            }
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    content = {}
                )

                if (tags.isNotEmpty()) {
                    FlowRow(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)) {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = (filter as? NoteFilter.Tag)?.name == tag.name,
                                onClick = {
                                    viewModel.selectFilter(
                                        if ((filter as? NoteFilter.Tag)?.name == tag.name) NoteFilter.All
                                        else NoteFilter.Tag(tag.name)
                                    )
                                },
                                label = { Text(tag.name) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                if (notes.isEmpty() && visibleLockedTasks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                query.isNotBlank() -> "Ничего не найдено"
                                filter == NoteFilter.Tasks -> "Пока нет задач"
                                else -> "Пока нет заметок"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visibleLockedTasks, key = { "locked-${it.id}" }) { task ->
                            LockedTaskCard(
                                onClick = {
                                    taskUnlockError = false
                                    taskToUnlock = task
                                }
                            )
                        }
                        items(notes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                onClick = {
                                    if (ProtectionSession.isUnlocked(note)) {
                                        onOpenEditor(note.id, note.isTask)
                                    } else {
                                        taskUnlockError = false
                                        taskToUnlock = note
                                    }
                                },
                                onDelete = { deleteCandidate = note },
                                onToggleCompleted = { viewModel.toggleCompleted(note) },
                                onToggleTimer = { viewModel.toggleTaskTimer(note) }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteCandidate?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Удалить заметку?") },
            text = { Text(note.title.ifBlank { "Без названия" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    scope.launch {
                        viewModel.delete(note)
                        val result = snackbarHostState.showSnackbar("Заметка удалена", actionLabel = "Отменить")
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(note.id)
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") }
            }
        )
    }

    if (showCreateNotebook) {
        NotebookNameDialog(
            title = "Новый блокнот",
            initial = "",
            confirmLabel = "Создать",
            onConfirm = { viewModel.createNotebook(it) },
            onDismiss = { showCreateNotebook = false }
        )
    }

    notebookToEdit?.let { notebook ->
        NotebookNameDialog(
            title = "Переименовать",
            initial = notebook.name,
            confirmLabel = "Сохранить",
            onConfirm = { viewModel.renameNotebook(notebook, it) },
            onDismiss = { notebookToEdit = null }
        )
    }

    notebookToDelete?.let { notebook ->
        AlertDialog(
            onDismissRequest = { notebookToDelete = null },
            title = { Text("Удалить блокнот?") },
            text = { Text("Заметки останутся в «Все заметки».") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNotebook(notebook)
                    notebookToDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { notebookToDelete = null }) { Text("Отмена") }
            }
        )
    }
    notebookToProtect?.let { notebook ->
        SetPasswordDialog(
            title = "Пароль блокнота",
            onDismiss = { notebookToProtect = null },
            onConfirm = { password ->
                viewModel.protectNotebook(notebook, password)
                notebookToProtect = null
            }
        )
    }

    notebookToUnlock?.let { notebook ->
        UnlockPasswordDialog(
            title = "Открыть «${notebook.name}»",
            showError = notebookUnlockError,
            onDismiss = { notebookToUnlock = null },
            onConfirm = { password ->
                viewModel.unlockNotebook(notebook, password) { unlocked ->
                    if (unlocked) {
                        notebookToUnlock = null
                        viewModel.selectFilter(NoteFilter.Notebook(notebook.id, notebook.name))
                        scope.launch { drawerState.close() }
                    } else {
                        notebookUnlockError = true
                    }
                }
            }
        )
    }

    notebookToUnprotect?.let { notebook ->
        UnlockPasswordDialog(
            title = "Снять защиту блокнота",
            showError = notebookRemoveError,
            onDismiss = { notebookToUnprotect = null },
            onConfirm = { password ->
                viewModel.removeNotebookProtection(notebook, password) { verified ->
                    if (verified) notebookToUnprotect = null else notebookRemoveError = true
                }
            }
        )
    }

    taskToUnlock?.let { note ->
        UnlockPasswordDialog(
            title = "Открыть защищённую задачу",
            showError = taskUnlockError,
            onDismiss = { taskToUnlock = null },
            onConfirm = { password ->
                viewModel.unlockTask(note, password) { unlocked ->
                    if (unlocked) {
                        taskToUnlock = null
                        onOpenEditor(note.id, true)
                    } else {
                        taskUnlockError = true
                    }
                }
            }
        )
    }
}

@Composable
private fun SortMenu(current: NoteSort, onSelect: (NoteSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Сортировка")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("По дате" + if (current == NoteSort.BY_DATE) " ✓" else "") },
                onClick = { onSelect(NoteSort.BY_DATE); open = false }
            )
            DropdownMenuItem(
                text = { Text("По имени" + if (current == NoteSort.BY_NAME) " ✓" else "") },
                onClick = { onSelect(NoteSort.BY_NAME); open = false }
            )
        }
    }
}

@Composable
private fun NotebookNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) { onConfirm(name); onDismiss() } },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun LockedTaskCard(onClick: () -> Unit) {
    val glass = LocalGlassTheme.current
    ElevatedCard(
        onClick = onClick,
        shape = if (glass.enabled) GlassPanelShape else CardDefaults.elevatedShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .glassBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column {
                Text("Защищённая задача", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Введите пароль для открытия",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleCompleted: () -> Unit,
    onToggleTimer: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val glass = LocalGlassTheme.current
    val completedColor = taskColorOption(note.taskColor).completedContainer
    // Этап 35: акцент задачи — цвет виден в заголовке и галочке.
    val accent = taskColorOption(note.taskColor).accent
    val cardColors = when {
        !note.isTask -> CardDefaults.elevatedCardColors()
        note.isCompleted -> CardDefaults.elevatedCardColors(
            // Стекло: выполненная задача тоже полупрозрачная.
            containerColor = if (glass.enabled) completedColor.copy(alpha = 0.55f) else completedColor,
            contentColor = Color(0xFF1D1B20)
        )
        else -> CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    ElevatedCard(
        onClick = onClick,
        colors = cardColors,
        shape = if (glass.enabled) GlassPanelShape else CardDefaults.elevatedShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .glassBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (note.isTask) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (note.isTask) {
                Checkbox(
                    checked = note.isCompleted,
                    onCheckedChange = { onToggleCompleted() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = accent,
                        uncheckedColor = accent
                    )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.isProtected) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Защищено паролем",
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (note.isFavorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Избранное",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = note.title.ifBlank { "Без названия" },
                        style = MaterialTheme.typography.titleMedium,
                        // Этап 35: активная задача — заголовок цветом задачи.
                        color = if (note.isTask && !note.isCompleted) accent
                        else LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.content.isNotBlank()) {
                    Text(
                        // HF-1: в карточках списка тоже отрисованный текст, без ** и [ ].
                        text = remember(note.content) { MarkdownLite.parse(note.content) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.isTask) {
                    TaskTimerIndicator(
                        elapsedMillis = note.taskElapsedMillis,
                        startedAt = note.taskTimerStartedAt,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = formatDateTime(note.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.65f)
                )
            }
            if (note.isTask) {
                IconButton(
                    onClick = onToggleTimer,
                    enabled = !note.isCompleted || note.taskTimerStartedAt != null
                ) {
                    Icon(
                        if (note.taskTimerStartedAt != null) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (note.taskTimerStartedAt != null) {
                            "Поставить таймер на паузу"
                        } else {
                            "Запустить таймер"
                        }
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun filterTitle(filter: NoteFilter): String = when (filter) {
    NoteFilter.All -> "Заметки"
    NoteFilter.Tasks -> "Задачи"
    NoteFilter.Favorites -> "Избранное"
    is NoteFilter.Notebook -> filter.name
    is NoteFilter.Tag -> "#" + filter.name
}
