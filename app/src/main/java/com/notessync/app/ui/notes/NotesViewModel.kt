package com.notessync.app.ui.notes

import com.notessync.app.data.local.model.nextChangeTimestamp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.room.withTransaction
import com.notessync.app.notes.actions.NoteActions
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.model.*
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.PasswordRecord
import com.notessync.app.security.ProtectionSession
import com.notessync.app.security.ProtectionState
import com.notessync.app.ui.tasks.TaskCompletion
import com.notessync.app.ui.tasks.TaskTimer
import com.notessync.app.widget.CalendarWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import com.notessync.app.ui.tasks.*
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoteSort { BY_DATE, BY_NAME, BY_DUE }

sealed interface NoteFilter {
    data object Trash : NoteFilter
    data object All : NoteFilter
    data object Tasks : NoteFilter
    data object Unfinished : NoteFilter
    data object Overdue : NoteFilter
    data object Favorites : NoteFilter
    data class Notebook(val id: Long, val name: String) : NoteFilter
    data class Tag(val name: String) : NoteFilter
}

internal data class NotesQueryKey(
    val search: String,
    val filter: NoteFilter,
    val sort: NoteSort,
    val protection: ProtectionState,
    val tasks: TaskFilters = TaskFilters(),
    val clock: Long = 0,
    val zone: String = ZoneId.systemDefault().id,
    val session: Long = 0
)
internal data class NotesResult(val key: NotesQueryKey, val notes: List<NoteCardEntry>)
internal fun NoteFilter.isUnfinished() = this == NoteFilter.Unfinished || this == NoteFilter.Overdue

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val noteDao = db.noteDao()
    private val notebookDao = db.notebookDao()
    private val tagDao = db.tagDao()
    val biometrics = com.notessync.app.security.biometrics.NotebookBiometricController(viewModelScope, db,
        com.notessync.app.security.biometrics.AndroidNotebookBiometricVault(application))

    internal val taskPasswords = com.notessync.app.security.TaskPasswordController(viewModelScope, db)
    internal val listProtection = com.notessync.app.security.ListTaskProtection(application, db, viewModelScope)
    fun cancelAuthentication() { biometrics.cancel(); taskPasswords.cancel(); listProtection.cancel() }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow<NoteFilter>(NoteFilter.All)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(NoteSort.BY_DATE)
    val sort: StateFlow<NoteSort> = _sort.asStateFlow()

    val notebooks: StateFlow<List<NotebookEntity>> = notebookDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagEntity>> = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _taskFilters = MutableStateFlow(TaskFilters())
    val taskFilters = _taskFilters.asStateFlow()
    private val _taskClock = MutableStateFlow(System.currentTimeMillis())
    val taskClock = _taskClock.asStateFlow()
    private val _listSession = MutableStateFlow(0L)
    val listSession = _listSession.asStateFlow()
    var listSessionValue by mutableLongStateOf(0L)
        private set
    @androidx.annotation.MainThread
    fun markListHidden() {
        // Publish the observable UI guard before allowing a query for this generation.
        listSessionValue += 1
        _listSession.value = listSessionValue
    }
    private val queryKeys = combine(
        combine(_query, _filter, _sort, ProtectionSession.state) { search, filter, sort, protection ->
            NotesQueryKey(search, filter, sort, protection)
        }, _taskFilters, _taskClock, _listSession
    ) { key, tasks, clock, session -> key.copy(tasks = if (key.filter.isUnfinished()) tasks else TaskFilters(),
        clock = if (key.filter.isUnfinished()) clock else 0, zone = ZoneId.systemDefault().id, session = session) }
        .distinctUntilChanged()

    internal val results: StateFlow<NotesResult?> = queryKeys
        .debounce(300)
        .flatMapLatest { key ->
            noteDao.rawCards(buildCardQuery(key.search, key.filter, key.sort, key.protection.notebookIds,
                key.protection.taskSyncIds, key.tasks, key.clock, ZoneId.of(key.zone))).map { NotesResult(key, it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)
    val notes: StateFlow<List<NoteCardEntry>> = results.map { it?.notes.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), emptyList())
    val nextTaskDue: StateFlow<Long?> = queryKeys.debounce(300).flatMapLatest { key ->
        if (!key.filter.isUnfinished()) flowOf(null)
        else noteDao.observeNextTaskDue(buildNextTaskDueQuery(key.search, key.protection.notebookIds,
            key.protection.taskSyncIds, key.tasks, key.clock, ZoneId.of(key.zone)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    fun chooseTaskFilters(value: TaskFilters) { _taskFilters.value = value.normalized() }
    fun refreshTaskClock() { _taskClock.value = System.currentTimeMillis() }

    // One metadata query for first photos, not one query per visible card or all image bytes.
    val firstPhotos = db.attachmentDao().observeFirstPhotos()
        .map { photos -> photos.associateBy { it.noteId } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), emptyMap())

    val lockedTasks: StateFlow<List<TaskUnlockTarget>> = combine(ProtectionSession.state, _filter) { protection, filter -> protection to filter }
        .flatMapLatest { (protection, filter) ->
            noteDao.rawLockedTaskTargets(
                buildLockedTaskTargetsQuery(protection.notebookIds, protection.taskSyncIds, filter == NoteFilter.Trash)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { _query.value = value }
    fun selectFilter(value: NoteFilter) { refreshTaskClock(); _filter.value = value }
    fun selectSort(value: NoteSort) { _sort.value = value }

    fun createNotebook(name: String) {
        viewModelScope.launch { notebookDao.upsert(NotebookEntity(name = name.trim())) }
    }

    fun renameNotebook(notebook: NotebookEntity, name: String) {
        viewModelScope.launch {
            db.withTransaction {
                val fresh = notebookDao.getById(notebook.id) ?: return@withTransaction
                if (fresh.isDeleted || fresh.syncId != notebook.syncId || !ProtectionSession.isUnlocked(fresh)) return@withTransaction
                notebookDao.update(fresh.copy(name = name.trim(), updatedAt = nextChangeTimestamp(fresh.updatedAt), dirty = true))
            }
        }
    }

    fun protectNotebook(notebook: NotebookEntity, password: String) {
        biometrics.cancel()
        viewModelScope.launch {
            val record = withContext(Dispatchers.Default) { PasswordProtector.create(password) }
            db.withTransaction {
                val fresh = notebookDao.getById(notebook.id) ?: return@withTransaction
                if (fresh.isDeleted || fresh.isProtected || fresh.syncId != notebook.syncId) return@withTransaction
                notebookDao.update(fresh.copy(isProtected = true, passwordHash = record.hash, passwordSalt = record.salt,
                    updatedAt = nextChangeTimestamp(fresh.updatedAt), dirty = true))
                ProtectionSession.unlockNotebook(fresh.id)
            }
        }
    }

    fun unlockNotebook(notebook: NotebookEntity, password: String, onResult: (Boolean) -> Unit) {
        biometrics.unlockPassword(notebook, password, onResult)
    }

    fun removeNotebookProtection(notebook: NotebookEntity, password: String, onResult: (Boolean) -> Unit) {
        biometrics.cancel()
        viewModelScope.launch {
            val fresh = notebookDao.getById(notebook.id)
            val expected = fresh?.passwordRecord()
            val verified = fresh?.syncId == notebook.syncId && verify(expected, password)
            val removed = db.withTransaction {
                val latest = notebookDao.getById(notebook.id)
                if (!verified || latest == null || latest.isDeleted || latest.syncId != notebook.syncId || latest.passwordRecord() != expected) false
                else {
                    notebookDao.update(latest.copy(isProtected = false, passwordHash = null, passwordSalt = null,
                        updatedAt = nextChangeTimestamp(latest.updatedAt), dirty = true))
                    ProtectionSession.lockNotebook(notebook.id)
                    true
                }
            }
            if (removed) biometrics.revoke(notebook.syncId) {}
            onResult(removed)
        }
    }

    fun lockNotebook(notebook: NotebookEntity) {
        biometrics.cancel()
        ProtectionSession.lockNotebook(notebook.id)
    }

    fun unlockTask(note: NoteEntity, password: String, onResult: (Boolean) -> Unit) =
        unlockTask(note.id, note.syncId, note.isDeleted, password, onResult)

    fun unlockTask(id: Long, syncId: String, deleted: Boolean, password: String, onResult: (Boolean) -> Unit) =
        taskPasswords.unlock(id, syncId, deleted, password, onResult)

    private suspend fun verify(record: PasswordRecord?, password: String): Boolean {
        if (record == null) return false
        return withContext(Dispatchers.Default) { PasswordProtector.verify(password, record) }
    }

    /** Заметки блокнота не удаляются, а переходят в «Все заметки». */
    fun deleteNotebook(notebook: NotebookEntity) {
        biometrics.cancel()
        viewModelScope.launch {
            val removed = db.withTransaction {
                val fresh = notebookDao.getById(notebook.id) ?: return@withTransaction false
                if (fresh.isDeleted || fresh.syncId != notebook.syncId || !ProtectionSession.isUnlocked(fresh)) return@withTransaction false
                noteDao.clearNotebook(fresh.id)
                notebookDao.softDelete(fresh.id)
                true
            }
            if (removed) {
                ProtectionSession.lockNotebook(notebook.id)
                biometrics.revoke(notebook.syncId) {}
                if ((_filter.value as? NoteFilter.Notebook)?.id == notebook.id) _filter.value = NoteFilter.All
            }
        }
    }

    override fun onCleared() { cancelAuthentication(); super.onCleared() }

    private val actions = NoteActions(application, db)

    suspend fun delete(note: NoteListEntry): Int = actions.apply(setOf(note.id), NoteActions.Action.TRASH, mapOf(note.id to note.syncId))
    suspend fun restore(noteId: Long, syncId: String? = null) {
        actions.apply(setOf(noteId), NoteActions.Action.RESTORE, syncId?.let { mapOf(noteId to it) })
    }
    suspend fun applyAction(ids: Set<Long>?, action: NoteActions.Action, identities: Map<Long, String>? = null): Int = actions.apply(ids, action, identities)

    fun toggleCompleted(note: NoteListEntry) {
        viewModelScope.launch {
            val changed = db.withTransaction {
                val fresh = noteDao.getById(note.id) ?: return@withTransaction false
                if (fresh.syncId != note.syncId || !fresh.isTask || fresh.isDeleted || fresh.isPurged || fresh.isCompleted != note.isCompleted || !actions.canAccess(fresh)) return@withTransaction false
                val updated = TaskCompletion.toggle(fresh)
                if (fresh.reminderAt != null || fresh.taskCountdownEndAt != null) com.notessync.app.reminders.AlarmReconciler.requireRecovery(getApplication())
                noteDao.update(updated)
                true
            }
            if (changed) {
                runCatching { com.notessync.app.reminders.AlarmReconciler.refreshNotes(getApplication(), db, setOf(note.id)) }
                CalendarWidgetProvider.refreshAll(getApplication())
            }
        }
    }

    fun toggleTaskTimer(note: NoteListEntry) {
        viewModelScope.launch {
            db.withTransaction {
                val fresh = noteDao.getById(note.id) ?: return@withTransaction
                if (fresh.syncId != note.syncId || !fresh.isTask || fresh.isDeleted || fresh.isPurged ||
                    (fresh.taskTimerStartedAt != null) != (note.taskTimerStartedAt != null) ||
                    !actions.canAccess(fresh) || (fresh.isCompleted && fresh.taskTimerStartedAt == null)) return@withTransaction
                val now = System.currentTimeMillis()
                val timer = TaskTimer.toggle(fresh.taskElapsedMillis, fresh.taskTimerStartedAt, now)
                noteDao.update(fresh.copy(taskElapsedMillis = timer.elapsedMillis, taskTimerStartedAt = timer.startedAt, updatedAt = nextChangeTimestamp(fresh.updatedAt, now), dirty = true))
            }
        }
    }

    companion object {
        /** Экранирование служебных символов LIKE (при ESCAPE '\\'). */
        internal fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun buildLockedTasksQuery(unlockedNotebookIds: Set<Long> = emptySet(), unlockedTaskIds: Set<String> = emptySet(),
            trash: Boolean = false): SupportSQLiteQuery = buildLockedTasksQueryInternal(unlockedNotebookIds, unlockedTaskIds, trash)

        fun buildLockedTaskTargetsQuery(unlockedNotebookIds: Set<Long> = emptySet(), unlockedTaskIds: Set<String> = emptySet(),
            trash: Boolean = false): SupportSQLiteQuery = buildLockedTasksQueryInternal(unlockedNotebookIds, unlockedTaskIds, trash,
                "notes.id, notes.syncId, notes.notebookId, notes.isDeleted")

        private fun buildLockedTasksQueryInternal(
            unlockedNotebookIds: Set<Long> = emptySet(),
            unlockedTaskIds: Set<String> = emptySet(),
            trash: Boolean = false, projection: String = "notes.*"
        ): SupportSQLiteQuery {
            val sql = StringBuilder(
                "SELECT $projection FROM notes WHERE notes.isPurged = 0 AND notes.isDeleted = ${if (trash) 1 else 0}" +
                    " AND notes.isTask = 1 AND notes.isProtected = 1"
            )
            val args = mutableListOf<Any>()
            if (unlockedTaskIds.isNotEmpty()) {
                sql.append(" AND notes.syncId NOT IN (${unlockedTaskIds.joinToString(",") { "?" }})")
                args.addAll(unlockedTaskIds)
            }
            sql.append(
                " AND (notes.notebookId IS NULL OR notes.notebookId NOT IN " +
                    "(SELECT id FROM notebooks WHERE isProtected = 1)"
            )
            if (unlockedNotebookIds.isNotEmpty()) {
                sql.append(" OR notes.notebookId IN (${unlockedNotebookIds.joinToString(",") { "?" }})")
                args.addAll(unlockedNotebookIds)
            }
            sql.append(") ORDER BY notes.updatedAt DESC")
            return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
        }

        /** Один SQL-запрос под фильтр + поиск + сортировку. Параметры только связанные. */
        fun buildQuery(search: String, filter: NoteFilter, sort: NoteSort,
            unlockedNotebookIds: Set<Long> = emptySet(), unlockedTaskIds: Set<String> = emptySet(),
            tasks: TaskFilters = TaskFilters(), now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()
        ): SupportSQLiteQuery = buildQueryInternal(search, filter, sort, unlockedNotebookIds, unlockedTaskIds, tasks, now, zone, false)

        fun buildCardQuery(search: String, filter: NoteFilter, sort: NoteSort,
            unlockedNotebookIds: Set<Long> = emptySet(), unlockedTaskIds: Set<String> = emptySet(),
            tasks: TaskFilters = TaskFilters(), now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()
        ): SupportSQLiteQuery = buildQueryInternal(search, filter, sort, unlockedNotebookIds, unlockedTaskIds, tasks, now, zone, false, true)

        fun buildNextTaskDueQuery(search: String, unlockedNotebookIds: Set<Long> = emptySet(),
            unlockedTaskIds: Set<String> = emptySet(), tasks: TaskFilters = TaskFilters(),
            now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()
        ): SupportSQLiteQuery = buildQueryInternal(search, NoteFilter.Unfinished, NoteSort.BY_DUE,
            unlockedNotebookIds, unlockedTaskIds, tasks.copy(due = TaskDueFilter.ALL), now, zone, true)

        private fun buildQueryInternal(
            search: String,
            filter: NoteFilter,
            sort: NoteSort,
            unlockedNotebookIds: Set<Long> = emptySet(),
            unlockedTaskIds: Set<String>, tasks: TaskFilters, now: Long, zone: ZoneId, minimum: Boolean, cards: Boolean = false
        ): SupportSQLiteQuery {
            val projection = if (minimum) "MIN(notes.taskDueAt)" else if (cards) NOTE_CARD_COLUMNS else "notes.*"
            val sql = StringBuilder("SELECT $projection FROM notes WHERE notes.isPurged = 0 AND notes.isDeleted = ${if (filter == NoteFilter.Trash) 1 else 0}")
            val args = mutableListOf<Any>()
            when (filter) {
                NoteFilter.All, NoteFilter.Trash -> Unit
                NoteFilter.Tasks -> sql.append(" AND notes.isTask = 1")
                NoteFilter.Unfinished, NoteFilter.Overdue -> {
                    sql.append(" AND notes.isTask = 1 AND notes.isCompleted = 0")
                    val choices = tasks.normalized()
                    val due = if (filter == NoteFilter.Overdue) TaskDueFilter.OVERDUE else choices.due
                    when (due) {
                        TaskDueFilter.ALL -> Unit
                        TaskDueFilter.NONE -> sql.append(" AND notes.taskDueAt IS NULL")
                        TaskDueFilter.OVERDUE -> { sql.append(" AND notes.taskDueAt < ?"); args.add(now) }
                        TaskDueFilter.UPCOMING -> { sql.append(" AND notes.taskDueAt >= ?"); args.add(now) }
                        TaskDueFilter.TODAY -> {
                            val (from, to) = TaskDeadlines.dayBounds(now, zone)
                            sql.append(" AND notes.taskDueAt >= ? AND notes.taskDueAt < ?"); args.add(from); args.add(to)
                        }
                    }
                    choices.priority?.let { sql.append(" AND notes.taskPriority = ?"); args.add(it) }
                    choices.notebookId?.let { id ->
                        if (id == 0L) sql.append(" AND notes.notebookId IS NULL")
                        else { sql.append(" AND notes.notebookId = ?"); args.add(id) }
                    }
                    choices.tagId?.let { id ->
                        sql.append(" AND EXISTS (SELECT 1 FROM note_tag_cross_ref JOIN tags ON tags.id = note_tag_cross_ref.tagId WHERE note_tag_cross_ref.noteId = notes.id AND tags.id = ? AND tags.isDeleted = 0)"); args.add(id)
                    }
                }
                NoteFilter.Favorites -> sql.append(" AND notes.isFavorite = 1")
                is NoteFilter.Notebook -> {
                    sql.append(" AND notes.notebookId = ?")
                    args.add(filter.id)
                }
                is NoteFilter.Tag -> {
                    sql.append(
                        " AND notes.id IN (SELECT noteId FROM note_tag_cross_ref" +
                            " JOIN tags ON tags.id = note_tag_cross_ref.tagId WHERE tags.name = ?)"
                    )
                    args.add(filter.name)
                }
            }
            sql.append(" AND (notes.isProtected = 0")
            if (unlockedTaskIds.isNotEmpty()) {
                sql.append(" OR (notes.syncId != '' AND notes.syncId IN (${unlockedTaskIds.joinToString(",") { "?" }}))")
                args.addAll(unlockedTaskIds)
            }
            sql.append(")")
            sql.append(
                " AND (notes.notebookId IS NULL OR notes.notebookId NOT IN " +
                    "(SELECT id FROM notebooks WHERE isProtected = 1)"
            )
            if (unlockedNotebookIds.isNotEmpty()) {
                sql.append(" OR notes.notebookId IN (${unlockedNotebookIds.joinToString(",") { "?" }})")
                args.addAll(unlockedNotebookIds)
            }
            sql.append(")")
            if (search.isNotBlank()) {
                // Аудит 2026-11-11: экранируем служебные символы LIKE — запрос «100%»
                // больше не превращается в «всё, что начинается на 100».
                sql.append(
                    " AND (notes.title LIKE '%' || ? || '%' ESCAPE '\\'" +
                        " OR notes.content LIKE '%' || ? || '%' ESCAPE '\\')"
                )
                val escaped = escapeLike(search.trim())
                args.add(escaped)
                args.add(escaped)
            }
            if (minimum) { sql.append(" AND notes.taskDueAt >= ?"); args.add(now) }
            else sql.append(when (sort) {
                NoteSort.BY_DATE -> " ORDER BY notes.isPinned DESC, notes.updatedAt DESC, notes.id DESC"
                NoteSort.BY_NAME -> " ORDER BY notes.isPinned DESC, notes.title COLLATE NOCASE ASC, notes.id DESC"
                NoteSort.BY_DUE -> " ORDER BY notes.isPinned DESC, notes.taskDueAt IS NULL ASC, notes.taskDueAt ASC, notes.taskPriority DESC, notes.updatedAt DESC, notes.id DESC"
            })
            return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
        }
    }
}

private fun NotebookEntity.passwordRecord(): PasswordRecord? {
    if (!isProtected || passwordHash == null || passwordSalt == null) return null
    return PasswordRecord(passwordHash, passwordSalt)
}
