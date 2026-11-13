package com.notessync.app.ui.screens

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.security.biometrics.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import com.notessync.app.notes.actions.NoteActions
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.filled.PushPin
import com.notessync.app.ui.notes.noteCardContainer
import com.notessync.app.ui.notes.noteAccent
import com.notessync.app.ui.notes.NoteCardMenu
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Update
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.settings.NotesLayout
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.notes.text.notePreviewText
import com.notessync.app.ui.notes.NoteGridCard
import com.notessync.app.ui.notes.NotePhotoPreview
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.ProtectionSession
import com.notessync.app.security.visibleNotes
import com.notessync.app.ui.common.SetPasswordDialog
import com.notessync.app.ui.common.UnlockPasswordDialog
import com.notessync.app.ui.common.formatDateTime
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.EventBusy
import com.notessync.app.ui.notes.isUnfinished
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NoteSort
import com.notessync.app.ui.notes.NotesViewModel
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.tasks.CountdownIndicator
import com.notessync.app.ui.tasks.TaskTimerIndicator
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.ui.theme.GlassPanelShape
import com.notessync.app.ui.theme.LocalGlassTheme
import com.notessync.app.ui.theme.glassBorder
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val result by viewModel.results.collectAsStateWithLifecycle()
    val notes = result?.notes.orEmpty()
    val taskFilters by viewModel.taskFilters.collectAsStateWithLifecycle()
    val taskTime by viewModel.taskClock.collectAsStateWithLifecycle()
    val listSession = viewModel.listSessionValue
    val photos by viewModel.firstPhotos.collectAsStateWithLifecycle()
    val cardsState = rememberLazyStaggeredGridState()
    val biometricRequest by viewModel.biometrics.request.collectAsStateWithLifecycle()
    val biometricBusy by viewModel.biometrics.busy.collectAsStateWithLifecycle()
    val biometricBindings by viewModel.biometrics.bindings.collectAsStateWithLifecycle()
    val biometricLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, biometricLifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.biometrics.cancel() }
        biometricLifecycle.addObserver(observer)
        onDispose { biometricLifecycle.removeObserver(observer); viewModel.biometrics.cancel() }
    }
    val lockedTasks by viewModel.lockedTasks.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val protectionState by ProtectionSession.state.collectAsStateWithLifecycle()

    val sessionPending = result == null || result?.key?.session != listSession
    val taskPending = sessionPending || (filter.isUnfinished() && (result?.key?.let {
        it.filter != filter || it.tasks != taskFilters || it.search != query || it.sort != sort
    } != false))
    val shownNotes = remember(notes, notebooks, protectionState, filter, taskPending, taskTime, taskFilters) {
        if (taskPending) emptyList() else {
            val visible = visibleNotes(notes, notebooks, protectionState, filter == NoteFilter.Trash)
            if (filter.isUnfinished() && result?.key?.clock != taskTime) filterCachedDeadlines(visible,
                if (filter == NoteFilter.Overdue) TaskDueFilter.OVERDUE else taskFilters.due, taskTime, java.time.ZoneId.systemDefault()) else visible
        }
    }
    TaskDeadlineClock(viewModel, filter, notes)

    val settingsContext = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember { SettingsRepository.get(settingsContext) }
    val displaySettings by settingsRepository.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerQuoteIndex by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(drawerState.isOpen, displaySettings.drawerQuote.enabled) {
        if (drawerState.isOpen && displaySettings.drawerQuote.enabled && displaySettings.drawerQuote.customText.isBlank())
            drawerQuoteIndex = kotlin.random.Random.nextInt(com.notessync.app.ui.quotes.QuotesData.quotes.size)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var pendingPurge by remember { mutableStateOf(false) }
    var purgeIds by remember { mutableStateOf<Set<Long>?>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query, filter) { selected = emptySet() }
    LaunchedEffect(shownNotes) { selected = selected.intersect(shownNotes.map { it.id }.toSet()) }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    fun select(note: NoteEntity) { if (!busy) selected = if (note.id in selected) selected - note.id else selected + note.id }
    fun apply(ids: Set<Long>?, action: NoteActions.Action) {
        if (busy) return
        busy = true
        scope.launch {
            val message = try {
                val count = viewModel.applyAction(ids, action)
                selected = emptySet()
                uiText("Обработано: %1\$s", (count))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                uiText("Не удалось выполнить действие. Повторите.")
            } finally { busy = false }
            snackbarHostState.showSnackbar(message)
        }
    }

    var deleteCandidate by remember { mutableStateOf<NoteEntity?>(null) }
    var showCreateNotebook by remember { mutableStateOf(false) }
    var notebookToEdit by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToProtect by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToUnlock by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookToEnrollBiometric by remember { mutableStateOf<NotebookEntity?>(null) }
    var biometricPasswordError by remember { mutableStateOf(false) }
    var notebookToUnprotect by remember { mutableStateOf<NotebookEntity?>(null) }
    var taskToUnlock by remember { mutableStateOf<NoteEntity?>(null) }
    var notebookUnlockError by remember { mutableStateOf(false) }
    var notebookRemoveError by remember { mutableStateOf(false) }
    var taskUnlockError by remember { mutableStateOf(false) }

    val notebookFilterId = (filter as? NoteFilter.Notebook)?.id
    val visibleLockedTasks = if (query.isBlank()) {
        lockedTasks.filter { task ->
            filter == NoteFilter.All || filter == NoteFilter.Tasks || filter == NoteFilter.Trash ||
                (notebookFilterId != null && task.notebookId == notebookFilterId)
        }
    } else {
        emptyList()
    }

    LaunchedEffect(deletedNoteEvents) {
        deletedNoteEvents?.collect { id ->
            if (id > 0) {
                onDeletedEventHandled()
                val result = snackbarHostState.showSnackbar(uiText("Заметка удалена"), actionLabel = uiText("Отменить"))
                if (result == SnackbarResult.ActionPerformed) viewModel.restore(id)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
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
                    IconButton(onClick = { showCreateNotebook = true }) {
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
                                notebookUnlockError = false
                                notebookToUnlock = notebook
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
                                                notebookToProtect = notebook
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
                                                    biometricPasswordError = false; notebookToEnrollBiometric = notebook
                                                } else scope.launch { snackbarHostState.showSnackbar(uiText("Биометрия недоступна. Проверьте отпечаток в настройках телефона.")) }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(uiText("Снять защиту")) },
                                            leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                notebookRemoveError = false
                                                notebookToUnprotect = notebook
                                            }
                                        )
                                    }
                                    if (unlocked) DropdownMenuItem(
                                        text = { Text(uiText("Переименовать")) },
                                        leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            notebookToEdit = notebook
                                        }
                                    )
                                    if (unlocked) DropdownMenuItem(
                                        text = { Text(uiText("Удалить")) },
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
                if (displaySettings.drawerQuote.enabled && drawerState.isOpen) {
                    com.notessync.app.ui.quotes.DrawerQuoteCard(displaySettings.drawerQuote, drawerQuoteIndex)
                }
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
                            Icon(Icons.Default.Menu, contentDescription = uiText("Меню"))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenAnalytics) {
                            Icon(Icons.Default.QueryStats, contentDescription = uiText("Аналитика"))
                        }
                        SortMenu(sort, viewModel::selectSort)
                        IconButton(onClick = onOpenSync) {
                            Icon(Icons.Default.Sync, contentDescription = uiText("Синхронизация"))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = uiText("Настройки"))
                        }
                    }
                )
            },
            floatingActionButton = {
                if (filter != NoteFilter.Trash && selected.isEmpty()) Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(
                        onClick = { onOpenEditor(-1L, true) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.CheckBox, contentDescription = uiText("Новая задача"))
                    }
                    FloatingActionButton(onClick = { onOpenEditor(-1L, false) }) {
                        Icon(Icons.Default.Add, contentDescription = uiText("Новая заметка"))
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
                            placeholder = { Text(uiText("Поиск")) },
                            modifier = Modifier.focusRequester(searchFocus),
                            leadingIcon = { IconButton(onClick = { searchFocus.requestFocus(); keyboard?.show() }) {
                                Icon(Icons.Default.Search, contentDescription = uiText("Поиск заметок"), tint = MaterialTheme.colorScheme.primary)
                            } },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { settingsRepository.setNotesLayout(
                                    if (displaySettings.notesLayout == NotesLayout.LIST) NotesLayout.GRID else NotesLayout.LIST) }) {
                                    Icon(if (displaySettings.notesLayout == NotesLayout.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                        if (displaySettings.notesLayout == NotesLayout.LIST) uiText("Показать сетку") else uiText("Показать список"))
                                }
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                                        Icon(Icons.Default.Close, contentDescription = uiText("Очистить"))
                                    }
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

                if (selected.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiText("Выбрано: %1\$s", (selected.size)), modifier = Modifier.padding(12.dp))
                    IconButton(onClick = { selected = emptySet() }, enabled = !busy) { Icon(Icons.Default.Close, uiText("Снять выделение")) }
                    IconButton(onClick = { selected = shownNotes.map { it.id }.toSet() }, enabled = !busy) { Icon(Icons.Default.SelectAll, uiText("Выбрать все найденные")) }
                    if (filter == NoteFilter.Trash) IconButton(onClick = { apply(selected, NoteActions.Action.RESTORE) }, enabled = !busy) { Icon(Icons.Default.RestoreFromTrash, uiText("Восстановить выбранные")) }
                    else IconButton(onClick = { apply(selected, if (shownNotes.filter { it.id in selected }.all { it.isPinned }) NoteActions.Action.UNPIN else NoteActions.Action.PIN) }, enabled = !busy) { Icon(Icons.Default.PushPin, uiText("Закрепить или открепить выбранные")) }
                    IconButton(onClick = {
                        if (filter == NoteFilter.Trash) { purgeIds = selected; pendingPurge = true }
                        else apply(selected, NoteActions.Action.TRASH)
                    }, enabled = !busy) { Icon(Icons.Default.Delete, if (filter == NoteFilter.Trash) uiText("Удалить выбранные навсегда") else uiText("В корзину")) }
                }
                if (filter == NoteFilter.Trash) {
                    FlowRow(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { apply(null, NoteActions.Action.RESTORE) }, enabled = !busy) { Text(uiText("Восстановить все")) }
                        TextButton(onClick = { purgeIds = null; pendingPurge = true }, enabled = !busy) { Text(uiText("Очистить корзину")) }
                    }
                    Text(uiText("Закрытые записи доступны после разблокировки."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }

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

                if (filter.isUnfinished()) TaskFiltersBar(if (filter == NoteFilter.Overdue) taskFilters.copy(due = TaskDueFilter.OVERDUE) else taskFilters, filter == NoteFilter.Overdue, notebooks, tags) {
                    viewModel.chooseTaskFilters(it)
                    if (filter != NoteFilter.Overdue || it.due != TaskDueFilter.OVERDUE) viewModel.selectFilter(NoteFilter.Unfinished)
                }
                if (shownNotes.isEmpty() && visibleLockedTasks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                sessionPending -> uiText("Загрузка…")
                                query.isNotBlank() -> uiText("Ничего не найдено")
                                filter == NoteFilter.Trash -> uiText("Корзина пуста")
                                filter.isUnfinished() -> uiText(if (taskPending) "Загрузка…" else "Нет подходящих задач")
                                filter == NoteFilter.Tasks -> uiText("Пока нет задач")
                                else -> uiText("Пока нет заметок")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    NotesCardsLayout(shownNotes, visibleLockedTasks, displaySettings.notesLayout, photos,
                        displaySettings.showTaskPriorities, state = cardsState,
                        onOpen = { note ->
                            if (selected.isNotEmpty() || filter == NoteFilter.Trash) select(note)
                            else if (ProtectionSession.isUnlocked(note)) onOpenEditor(note.id, note.isTask)
                            else { taskUnlockError = false; taskToUnlock = note }
                        }, onUnlock = { task -> taskUnlockError = false; taskToUnlock = task },
                        onDelete = { if (filter == NoteFilter.Trash) { purgeIds = setOf(it.id); pendingPurge = true } else deleteCandidate = it }, onToggleCompleted = viewModel::toggleCompleted,
                        onToggleTimer = viewModel::toggleTaskTimer, selected = selected, onSelect = ::select,
                        onPin = { apply(setOf(it.id), if (it.isPinned) NoteActions.Action.UNPIN else NoteActions.Action.PIN) }, onRestore = { apply(setOf(it.id), NoteActions.Action.RESTORE) }, deadlineNow = taskTime)

                }
            }
        }
    }

    if (pendingPurge) AlertDialog(
        onDismissRequest = { pendingPurge = false },
        title = { Text(if (purgeIds == null) uiText("Очистить корзину?") else uiText("Удалить навсегда?")) },
        text = { Text(uiText("Текст и вложения будут удалены. Восстановить из корзины нельзя. Закрытые записи останутся до разблокировки.")) },
        confirmButton = { TextButton(onClick = { pendingPurge = false; apply(purgeIds, NoteActions.Action.PURGE) }, enabled = !busy) { Text(uiText("Удалить навсегда")) } },
        dismissButton = { TextButton(onClick = { pendingPurge = false }) { Text(uiText("Отмена")) } }
    )

    deleteCandidate?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(uiText("Удалить заметку?")) },
            text = { Text(note.title.ifBlank { uiText("Без названия") }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    scope.launch {
                        try {
                            if (viewModel.delete(note) > 0) {
                                val result = snackbarHostState.showSnackbar(uiText("Заметка в корзине"), actionLabel = uiText("Отменить"))
                                if (result == SnackbarResult.ActionPerformed) viewModel.restore(note.id)
                            }
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            snackbarHostState.showSnackbar(uiText("Не удалось выполнить действие. Повторите."))
                        }
                    }
                }) { Text(uiText("Удалить")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(uiText("Отмена")) }
            }
        )
    }

    if (showCreateNotebook) {
        NotebookNameDialog(
            title = uiText("Новый блокнот"),
            initial = "",
            confirmLabel = uiText("Создать"),
            onConfirm = { viewModel.createNotebook(it) },
            onDismiss = { showCreateNotebook = false }
        )
    }

    notebookToEdit?.let { notebook ->
        NotebookNameDialog(
            title = uiText("Переименовать"),
            initial = notebook.name,
            confirmLabel = uiText("Сохранить"),
            onConfirm = { viewModel.renameNotebook(notebook, it) },
            onDismiss = { notebookToEdit = null }
        )
    }

    notebookToDelete?.let { notebook ->
        AlertDialog(
            onDismissRequest = { notebookToDelete = null },
            title = { Text(uiText("Удалить блокнот?")) },
            text = { Text(uiText("Заметки останутся в «Все заметки».")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNotebook(notebook)
                    notebookToDelete = null
                }) { Text(uiText("Удалить")) }
            },
            dismissButton = {
                TextButton(onClick = { notebookToDelete = null }) { Text(uiText("Отмена")) }
            }
        )
    }
    notebookToProtect?.let { notebook ->
        SetPasswordDialog(
            title = uiText("Пароль блокнота"),
            onDismiss = { notebookToProtect = null },
            onConfirm = { password ->
                viewModel.protectNotebook(notebook, password)
                notebookToProtect = null
            }
        )
    }

    notebookToEnrollBiometric?.let { notebook ->
        UnlockPasswordDialog(title = uiText("Включить биометрию: пароль блокнота"), showError = biometricPasswordError,
            onDismiss = { notebookToEnrollBiometric = null; viewModel.biometrics.cancel() }, busy = biometricBusy,
            onConfirm = { password -> viewModel.biometrics.prepare(notebook, true, password) { ready ->
                when (ready) {
                    NotebookBiometricController.PrepareResult.READY -> notebookToEnrollBiometric = null
                    NotebookBiometricController.PrepareResult.PASSWORD -> biometricPasswordError = true
                    else -> {
                        notebookToEnrollBiometric = null
                        scope.launch { snackbarHostState.showSnackbar(uiText("Биометрия недоступна. Откройте блокнот паролем и повторите.")) }
                    }
                }
            } }
        )
    }
    biometricRequest?.let { request ->
        NotebookBiometricPrompt(request,
            onAuthenticated = { cipher -> viewModel.biometrics.authenticate(request, cipher) { accepted ->
                if (accepted && !request.challenge.enroll) {
                    notebookToUnlock = null
                    val book = notebooks.firstOrNull { it.id == request.notebookId }
                    if (book != null) { viewModel.selectFilter(NoteFilter.Notebook(book.id, book.name)); scope.launch { drawerState.close() } }
                } else scope.launch { snackbarHostState.showSnackbar(if (accepted) uiText("Биометрия включена") else uiText("Биометрия не подтверждена. Используйте пароль.")) }
            } },
            onFallback = { viewModel.biometrics.cancelIfPending(request) },
            onDisposed = { viewModel.biometrics.cancelIfPending(request) })
    }

    notebookToUnlock?.let { notebook ->
        val biometricTag = remember(notebook) { NotebookBiometricBinding.from(notebook)?.credentialTag }
        UnlockPasswordDialog(
            title = uiText("Открыть «%1\$s»", (notebook.name)),
            showError = notebookUnlockError,
            busy = biometricBusy,
            alternative = {
                if (biometricTag != null && biometricBindings[notebook.syncId] == biometricTag) TextButton(
                    enabled = !biometricBusy,
                    onClick = { viewModel.biometrics.prepare(notebook, false) { ready -> if (ready != NotebookBiometricController.PrepareResult.READY) scope.launch { snackbarHostState.showSnackbar(uiText("Биометрия недоступна. Введите пароль.")) } } }
                ) { Text(uiText("Открыть по биометрии")) }
            },
            onDismiss = { notebookToUnlock = null; viewModel.biometrics.cancel() },
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
            title = uiText("Снять защиту блокнота"),
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
            title = uiText("Открыть защищённую задачу"),
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
            Icon(Icons.Default.Sort, contentDescription = uiText("Сортировка"))
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
                label = { Text(uiText("Название")) },
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
            TextButton(onClick = onDismiss) { Text(uiText("Отмена")) }
        }
    )
}

@Composable
private fun LockedTaskCard(onClick: () -> Unit, horizontalPadding: Dp = 16.dp) {
    val glass = LocalGlassTheme.current
    ElevatedCard(
        onClick = onClick,
        shape = if (glass.enabled) GlassPanelShape else CardDefaults.elevatedShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 6.dp)
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
                Text(uiText("Защищённая задача"), style = MaterialTheme.typography.titleMedium)
                Text(
                    uiText("Введите пароль для открытия"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    showPriority: Boolean = true,
    photo: AttachmentEntity? = null,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleCompleted: () -> Unit,
    onToggleTimer: () -> Unit,
    onSelect: () -> Unit = {}, onPin: () -> Unit = {}, onRestore: () -> Unit = {},
    selecting: Boolean = false, selected: Boolean = false, active: Boolean = true, deadlineNow: Long = System.currentTimeMillis()
) {
    var menuOpen by remember { mutableStateOf(false) }
    val glass = LocalGlassTheme.current
    val completedColor = taskColorOption(note.taskColor).completedContainer
    // Этап 35: акцент задачи — цвет виден в заголовке и галочке.
    val accent = noteAccent(note)
    val cardColors = when {
        !note.isTask -> CardDefaults.elevatedCardColors(containerColor = noteCardContainer(note), contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        note.isCompleted -> CardDefaults.elevatedCardColors(
            // Стекло: выполненная задача тоже полупрозрачная.
            containerColor = if (glass.enabled) completedColor.copy(alpha = 0.55f) else completedColor,
            contentColor = Color(0xFF1D1B20)
        )
        else -> CardDefaults.elevatedCardColors(
            containerColor = noteCardContainer(note),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    ElevatedCard(
        colors = cardColors,
        shape = if (glass.enabled) GlassPanelShape else CardDefaults.elevatedShape,
        modifier = Modifier
            .fillMaxWidth().testTag("note-card-${note.id}").combinedClickable(onClick = onClick, onLongClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .glassBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (note.isTask) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(selected, { onSelect() }, modifier = Modifier.semantics { contentDescription = uiText("Выбрать: ") + notePreviewText(note.title, 128) })
            if (note.isTask && !selecting && !note.isDeleted) {
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
                    if (note.isTask && showPriority && note.taskPriority != 0) {
                        Icon(androidx.compose.material.icons.Icons.Default.Flag,
                            contentDescription = com.notessync.app.ui.tasks.TaskPriority.from(note.taskPriority).label,
                            tint = com.notessync.app.ui.tasks.priorityColor(note.taskPriority), modifier = Modifier.padding(end = 4.dp))
                    }
                    if (note.isProtected) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = uiText("Защищено паролем"),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (note.isPinned) Icon(Icons.Default.PushPin, uiText("Закреплено"), modifier = Modifier.padding(end = 4.dp))
                    if (note.isFavorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = uiText("Избранное"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = notePreviewText(note.title, 256).ifBlank { uiText("Без названия") },
                        style = MaterialTheme.typography.titleMedium,
                        // Этап 35: активная задача — заголовок цветом задачи.
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.tablesData.isNotEmpty()) Text(uiText("Таблица"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (photo != null) NotePhotoPreview(photo, 120)
                if (note.content.isNotBlank()) {
                    Text(
                        // HF-1: в карточках списка тоже отрисованный текст, без ** и [ ].
                        text = remember(note.content, note.textFormat) { MarkdownLite.parse(notePreviewText(note.content), formatting = note.textFormat) },
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
                        modifier = Modifier.padding(top = 4.dp),
                        contentColor = accent, animateIcon = true, active = active
                    )
                    if (note.taskCountdownMillis > 0L || note.taskCountdownEndAt != null) {
                        CountdownIndicator(
                            countdownMillis = note.taskCountdownMillis,
                            endAt = note.taskCountdownEndAt,
                            modifier = Modifier.padding(top = 4.dp),
                            animateIcon = true, contentColor = accent, active = active
                        )
                    }
                }
                if (note.isTask) TaskDeadlineLabel(note.taskDueAt, deadlineNow, note.isCompleted, accent)
                Text(
                    text = formatDateTime(note.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.65f)
                )
            }
            if (note.isTask && !selecting && !note.isDeleted) {
                IconButton(
                    onClick = onToggleTimer,
                    enabled = !note.isCompleted || note.taskTimerStartedAt != null
                ) {
                    Icon(
                        if (note.taskTimerStartedAt != null) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (note.taskTimerStartedAt != null) {
                            uiText("Поставить таймер на паузу")
                        } else {
                            uiText("Запустить таймер")
                        }
                    )
                }
            }
            NoteCardMenu(note, onDelete, onSelect, onPin, onRestore)
        }
    }
}

private fun filterTitle(filter: NoteFilter): String = when (filter) {
    NoteFilter.Trash -> uiText("Корзина")
    NoteFilter.All -> uiText("Заметки")
    NoteFilter.Tasks -> uiText("Задачи")
    NoteFilter.Unfinished -> uiText("Невыполненные")
    NoteFilter.Overdue -> uiText("Просроченные")
    NoteFilter.Favorites -> uiText("Избранное")
    is NoteFilter.Notebook -> filter.name
    is NoteFilter.Tag -> "#" + filter.name
}



/** One lazy state for both layouts preserves the same anchor across a lane-count change. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NotesCardsLayout(notes: List<NoteEntity>, locked: List<NoteEntity>, layout: NotesLayout,
    photos: Map<Long, AttachmentEntity>, showPriority: Boolean,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    onOpen: (NoteEntity) -> Unit, onUnlock: (NoteEntity) -> Unit, onDelete: (NoteEntity) -> Unit,
    onToggleCompleted: (NoteEntity) -> Unit, onToggleTimer: (NoteEntity) -> Unit,
    selected: Set<Long> = emptySet(), onSelect: (NoteEntity) -> Unit = {},
    onPin: (NoteEntity) -> Unit = {}, onRestore: (NoteEntity) -> Unit = {}, deadlineNow: Long = System.currentTimeMillis()
) {
    val grid = layout == NotesLayout.GRID
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (grid && maxWidth >= 280.dp) 2 else 1
        var previousColumns by rememberSaveable { mutableIntStateOf(columns) }
        var previousLayout by rememberSaveable { mutableStateOf(layout) }
        var anchor by rememberSaveable { mutableIntStateOf(state.firstVisibleItemIndex) }
        LaunchedEffect(layout, columns, state) {
            if (previousLayout != layout || previousColumns != columns) {
                state.scrollToItem(anchor.coerceIn(0, (notes.size + locked.size - 1).coerceAtLeast(0)), 0)
                previousLayout = layout
                previousColumns = columns
            }
            snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { anchor = it }
        }
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), state = state,
            modifier = Modifier.fillMaxSize().testTag("notes-cards"),
            contentPadding = PaddingValues(start = if (grid) 16.dp else 0.dp, end = if (grid) 16.dp else 0.dp,
                top = if (grid) 6.dp else 0.dp, bottom = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = if (grid) 12.dp else 0.dp
        ) {
            items(locked, key = { "locked-${it.id}" }, span = { StaggeredGridItemSpan.FullLine }) { task ->
                LockedTaskCard({ onUnlock(task) }, horizontalPadding = if (grid) 0.dp else 16.dp)
            }
            items(notes, key = { "note-${it.id}" }, contentType = { if (grid) "grid" else "list" }) { note ->
                val active by remember(state, note.id) { derivedStateOf { state.layoutInfo.visibleItemsInfo.any { it.key == "note-${note.id}" } } }
                if (grid) NoteGridCard(note, photos[note.id], showPriority,
                    { onOpen(note) }, { onDelete(note) }, { onToggleCompleted(note) }, { onToggleTimer(note) },
                    { onSelect(note) }, { onPin(note) }, { onRestore(note) }, selected.isNotEmpty(), note.id in selected, active, deadlineNow)
                else NoteCard(note, showPriority, photos[note.id], { onOpen(note) }, { onDelete(note) },
                    { onToggleCompleted(note) }, { onToggleTimer(note) }, { onSelect(note) }, { onPin(note) },
                    { onRestore(note) }, selected.isNotEmpty(), note.id in selected, active, deadlineNow)
            }
        }
    }
}
