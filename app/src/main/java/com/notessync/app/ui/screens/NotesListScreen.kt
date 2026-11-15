package com.notessync.app.ui.screens

import com.notessync.app.ui.home.*
import com.notessync.app.i18n.uiText
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.settings.NotesLayout
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.data.local.model.*
import com.notessync.app.security.ProtectionSession
import com.notessync.app.security.visibleNotes
import com.notessync.app.ui.notes.isUnfinished
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NotesViewModel
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
    deletedNoteEvents: StateFlow<NoteIdentity?>? = null,
    onDeletedEventHandled: () -> Unit = {},
    viewModel: NotesViewModel = viewModel(),
    onOpenAgenda: () -> Unit = onOpenCalendar
) {
    val settingsContext = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember { SettingsRepository.get(settingsContext) }
    val displaySettings by settingsRepository.state.collectAsStateWithLifecycle()
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
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.cancelAuthentication() }
        biometricLifecycle.addObserver(observer)
        onDispose { biometricLifecycle.removeObserver(observer); viewModel.cancelAuthentication() }
    }
    val lockedResult by viewModel.lockedResults.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val protectionState by ProtectionSession.state.collectAsStateWithLifecycle()
    val diary = (filter as? NoteFilter.Tag)?.name == com.notessync.app.notes.diary.DiaryEntries.TAG_NAME
    val lockedTasks = lockedResult?.takeIf {
        it.scope.filter == filter && it.scope.archiveCompleted == displaySettings.archiveCompletedTasks &&
            it.scope.session == listSession && it.scope.protection == protectionState
    }?.targets.orEmpty()

    val sessionPending = result == null || result?.key?.session != listSession
    val taskPending = sessionPending || (result?.key?.let {
        it.filter != filter || (filter.isUnfinished() && it.tasks != taskFilters) || it.search != query ||
            it.sort != sort || it.archiveCompleted != displaySettings.archiveCompletedTasks
    } != false)
    val shownNotes = remember(notes, notebooks, protectionState, filter, taskPending, taskTime, taskFilters) {
        if (taskPending) emptyList() else {
            val visible = visibleNotes(notes, notebooks, protectionState, filter == NoteFilter.Trash)
            if (filter.isUnfinished() && result?.key?.clock != taskTime) filterCachedDeadlines(visible,
                if (filter == NoteFilter.Overdue) TaskDueFilter.OVERDUE else taskFilters.due, taskTime, java.time.ZoneId.systemDefault()) else visible
        }
    }
    TaskDeadlineClock(viewModel, filter, notes)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerQuoteIndex by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(drawerState.isOpen, displaySettings.drawerQuote.enabled) {
        if (drawerState.isOpen && displaySettings.drawerQuote.enabled && displaySettings.drawerQuote.customText.isBlank())
            drawerQuoteIndex = kotlin.random.Random.nextInt(com.notessync.app.ui.quotes.QuotesData.quotes.size)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notebookUi = remember { HomeNotebookState() }

    var selected by remember { mutableStateOf(emptyMap<Long, String>()) }
    var pendingPurge by remember { mutableStateOf(false) }
    var purgeTargets by remember { mutableStateOf<Map<Long, String>?>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query, filter) { selected = emptyMap() }
    LaunchedEffect(shownNotes) {
        val current = shownNotes.associate { it.id to it.syncId }
        selected = selected.filter { current[it.key] == it.value }
    }
    BackHandler(selected.isNotEmpty()) { selected = emptyMap() }
    fun select(note: NoteCardEntry) { if (!busy) selected = if (selected[note.id] == note.syncId) selected - note.id else selected + (note.id to note.syncId) }
    fun apply(ids: Set<Long>?, action: NoteActions.Action, identities: Map<Long, String>? = if (ids == null) null else selected.filterKeys { it in ids }) {
        if (busy) return
        busy = true
        scope.launch {
            val message = try {
                val count = viewModel.applyAction(ids, action, identities)
                selected = emptyMap()
                uiText("Обработано: %1\$s", (count))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                uiText("Не удалось выполнить действие. Повторите.")
            } finally { busy = false }
            snackbarHostState.showSnackbar(message)
        }
    }

    var deleteCandidate by remember { mutableStateOf<NoteCardEntry?>(null) }

    val notebookFilterId = (filter as? NoteFilter.Notebook)?.id
    val visibleLockedTasks = if (query.isBlank()) {
        lockedTasks.filter { task ->
            filter == NoteFilter.All || filter == NoteFilter.Tasks || filter == NoteFilter.Completed || filter == NoteFilter.Trash ||
                (notebookFilterId != null && task.notebookId == notebookFilterId)
        }
    } else {
        emptyList()
    }

    LaunchedEffect(deletedNoteEvents) {
        deletedNoteEvents?.collect { event ->
            if (event != null && event.id > 0) {
                onDeletedEventHandled()
                val result = snackbarHostState.showSnackbar(uiText("Заметка удалена"), actionLabel = uiText("Отменить"))
                if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.id, event.syncId)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NotesDrawer(viewModel, notebookUi, filter, notebooks, protectionState, biometricBindings,
                displaySettings, drawerQuoteIndex, drawerState, scope, snackbarHostState, settingsContext,
                onOpenCalendar = onOpenCalendar, onOpenQuotes = onOpenQuotes, onOpenAgenda = onOpenAgenda)
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(filterTitle(filter), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = uiText("Меню"))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenAnalytics) {
                            Icon(Icons.Default.QueryStats, contentDescription = uiText("Аналитика"))
                        }
                        IconButton(onClick = onOpenSync) {
                            Icon(Icons.Default.Sync, contentDescription = uiText("Синхронизация"))
                        }
                        HomeActionsMenu(sort, viewModel::selectSort, filter, viewModel::selectFilter, onOpenSettings, onOpenAgenda)
                    }
                )
            },
            floatingActionButton = {
                if (filter != NoteFilter.Trash && filter != NoteFilter.Completed && selected.isEmpty()) Column(horizontalAlignment = Alignment.End) {
                    if (!diary) SmallFloatingActionButton(
                        onClick = { onOpenEditor(-1L, true) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.CheckBox, contentDescription = uiText("Новая задача"))
                    }
                    FloatingActionButton(onClick = {
                        if (diary) viewModel.createDiaryEntry({ onOpenEditor(it, false) }, {
                            scope.launch { snackbarHostState.showSnackbar(uiText("Не удалось создать запись дневника. Повторите.")) }
                        }) else onOpenEditor(-1L, false)
                    }) {
                        if (viewModel.creatingDiary) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, contentDescription = uiText(if (diary) "Новая запись дневника" else "Новая заметка"))
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
                    IconButton(onClick = { selected = emptyMap() }, enabled = !busy) { Icon(Icons.Default.Close, uiText("Снять выделение")) }
                    IconButton(onClick = { selected = shownNotes.associate { it.id to it.syncId } }, enabled = !busy) { Icon(Icons.Default.SelectAll, uiText("Выбрать все найденные")) }
                    if (filter == NoteFilter.Trash) IconButton(onClick = { apply(selected.keys, NoteActions.Action.RESTORE, selected) }, enabled = !busy) { Icon(Icons.Default.RestoreFromTrash, uiText("Восстановить выбранные")) }
                    else IconButton(onClick = { apply(selected.keys, if (shownNotes.filter { it.id in selected }.all { it.isPinned }) NoteActions.Action.UNPIN else NoteActions.Action.PIN) }, enabled = !busy) { Icon(Icons.Default.PushPin, uiText("Закрепить или открепить выбранные")) }
                    IconButton(onClick = {
                        if (filter == NoteFilter.Trash) { purgeTargets = selected.toMap(); pendingPurge = true }
                        else apply(selected.keys, NoteActions.Action.TRASH, selected)
                    }, enabled = !busy) { Icon(Icons.Default.Delete, if (filter == NoteFilter.Trash) uiText("Удалить выбранные навсегда") else uiText("В корзину")) }
                }
                if (filter == NoteFilter.Trash) {
                    FlowRow(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { apply(null, NoteActions.Action.RESTORE) }, enabled = !busy) { Text(uiText("Восстановить все")) }
                        TextButton(onClick = { purgeTargets = null; pendingPurge = true }, enabled = !busy) { Text(uiText("Очистить корзину")) }
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
                                taskPending -> uiText("Загрузка…")
                                query.isNotBlank() -> uiText("Ничего не найдено")
                                filter == NoteFilter.Trash -> uiText("Корзина пуста")
                                filter.isUnfinished() -> uiText(if (taskPending) "Загрузка…" else "Нет подходящих задач")
                                filter == NoteFilter.Tasks -> uiText("Пока нет задач")
                                filter == NoteFilter.Completed -> uiText("Нет выполненных задач")
                                diary -> uiText("В дневнике пока нет записей")
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
                            else { notebookUi.taskUnlockError = false; notebookUi.taskToUnlock = note.unlockTarget() }
                        }, onUnlock = { task -> notebookUi.taskUnlockError = false; notebookUi.taskToUnlock = task },
                        onDelete = { if (filter == NoteFilter.Trash) { purgeTargets = mapOf(it.id to it.syncId); pendingPurge = true } else deleteCandidate = it }, onToggleCompleted = viewModel::toggleCompleted,
                        onToggleTimer = viewModel::toggleTaskTimer, selected = selected.keys, onSelect = ::select,
                        onPin = { apply(setOf(it.id), if (it.isPinned) NoteActions.Action.UNPIN else NoteActions.Action.PIN, mapOf(it.id to it.syncId)) }, onRestore = { apply(setOf(it.id), NoteActions.Action.RESTORE, mapOf(it.id to it.syncId)) }, deadlineNow = taskTime, onProtection = { note ->
                            notebookUi.taskProtectionError = false
                            if (note.isProtected) notebookUi.taskToUnprotect = note else notebookUi.taskToProtect = note
                        })

                }
            }
        }
    }

    if (pendingPurge) AlertDialog(
        onDismissRequest = { pendingPurge = false },
        title = { Text(if (purgeTargets == null) uiText("Очистить корзину?") else uiText("Удалить навсегда?")) },
        text = { Text(uiText("Текст и вложения будут удалены. Восстановить из корзины нельзя. Закрытые записи останутся до разблокировки.")) },
        confirmButton = { TextButton(onClick = { pendingPurge = false; apply(purgeTargets?.keys, NoteActions.Action.PURGE, purgeTargets) }, enabled = !busy) { Text(uiText("Удалить навсегда")) } },
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
                                if (result == SnackbarResult.ActionPerformed) viewModel.restore(note.id, note.syncId)
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

    HomeNotebookDialogs(viewModel, notebookUi, notebooks, biometricBindings, biometricBusy, biometricRequest,
        drawerState, scope, snackbarHostState, onOpenEditor)

}
