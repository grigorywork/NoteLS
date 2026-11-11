package com.notessync.app.ui.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoteSort { BY_DATE, BY_NAME }

sealed interface NoteFilter {
    data object All : NoteFilter
    data object Tasks : NoteFilter
    data object Favorites : NoteFilter
    data class Notebook(val id: Long, val name: String) : NoteFilter
    data class Tag(val name: String) : NoteFilter
}

private data class NotesQueryKey(
    val search: String,
    val filter: NoteFilter,
    val sort: NoteSort,
    val protection: ProtectionState
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val noteDao = db.noteDao()
    private val notebookDao = db.notebookDao()
    private val tagDao = db.tagDao()

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

    val notes: StateFlow<List<NoteEntity>> =
        combine(_query, _filter, _sort, ProtectionSession.state) { query, filter, sort, protection ->
            NotesQueryKey(query, filter, sort, protection)
        }
            // Аудит 2026-11-11: не дёргаем БД на каждый символ — ждём паузу ввода.
            .debounce(300)
            .flatMapLatest { key ->
                noteDao.rawQuery(
                    buildQuery(
                        key.search,
                        key.filter,
                        key.sort,
                        key.protection.notebookIds,
                        key.protection.taskSyncIds
                    )
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lockedTasks: StateFlow<List<NoteEntity>> = ProtectionSession.state
        .flatMapLatest { protection ->
            noteDao.rawQuery(
                buildLockedTasksQuery(protection.notebookIds, protection.taskSyncIds)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { _query.value = value }
    fun selectFilter(value: NoteFilter) { _filter.value = value }
    fun selectSort(value: NoteSort) { _sort.value = value }

    fun createNotebook(name: String) {
        viewModelScope.launch { notebookDao.upsert(NotebookEntity(name = name.trim())) }
    }

    fun renameNotebook(notebook: NotebookEntity, name: String) {
        viewModelScope.launch {
            notebookDao.update(
                notebook.copy(name = name.trim(), updatedAt = System.currentTimeMillis(), dirty = true)
            )
        }
    }

    fun protectNotebook(notebook: NotebookEntity, password: String) {
        viewModelScope.launch {
            val record = withContext(Dispatchers.Default) { PasswordProtector.create(password) }
            notebookDao.update(
                notebook.copy(
                    isProtected = true,
                    passwordHash = record.hash,
                    passwordSalt = record.salt,
                    updatedAt = System.currentTimeMillis(),
                    dirty = true
                )
            )
            ProtectionSession.unlockNotebook(notebook.id)
        }
    }

    fun unlockNotebook(notebook: NotebookEntity, password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val unlocked = verify(notebook.passwordRecord(), password)
            if (unlocked) ProtectionSession.unlockNotebook(notebook.id)
            onResult(unlocked)
        }
    }

    fun removeNotebookProtection(
        notebook: NotebookEntity,
        password: String,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val verified = verify(notebook.passwordRecord(), password)
            if (verified) {
                notebookDao.update(
                    notebook.copy(
                        isProtected = false,
                        passwordHash = null,
                        passwordSalt = null,
                        updatedAt = System.currentTimeMillis(),
                        dirty = true
                    )
                )
                ProtectionSession.lockNotebook(notebook.id)
            }
            onResult(verified)
        }
    }

    fun lockNotebook(notebook: NotebookEntity) {
        ProtectionSession.lockNotebook(notebook.id)
    }

    fun unlockTask(note: NoteEntity, password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val record = if (note.isProtected && note.passwordHash != null && note.passwordSalt != null) {
                PasswordRecord(note.passwordHash, note.passwordSalt)
            } else {
                null
            }
            val unlocked = verify(record, password)
            if (unlocked) ProtectionSession.unlockTask(note.syncId)
            onResult(unlocked)
        }
    }

    private suspend fun verify(record: PasswordRecord?, password: String): Boolean {
        if (record == null) return false
        return withContext(Dispatchers.Default) { PasswordProtector.verify(password, record) }
    }

    /** Заметки блокнота не удаляются, а переходят в «Все заметки». */
    fun deleteNotebook(notebook: NotebookEntity) {
        viewModelScope.launch {
            noteDao.clearNotebook(notebook.id)
            notebookDao.softDelete(notebook.id)
            if ((_filter.value as? NoteFilter.Notebook)?.id == notebook.id) {
                _filter.value = NoteFilter.All
            }
        }
    }

    suspend fun delete(note: NoteEntity) {
        noteDao.softDelete(note.id)
        if (note.dayKey != null) CalendarWidgetProvider.refreshAll(getApplication())
    }

    suspend fun restore(noteId: Long) {
        noteDao.restore(noteId)
        CalendarWidgetProvider.refreshAll(getApplication())
    }

    fun toggleCompleted(note: NoteEntity) {
        if (!note.isTask) return
        val updated = TaskCompletion.toggle(note)
        viewModelScope.launch {
            noteDao.update(updated)
            if (updated.isCompleted) {
                ReminderScheduler.cancel(getApplication(), note.id)
                ReminderScheduler.cancelTimer(getApplication(), note.id)
            }
            CalendarWidgetProvider.refreshAll(getApplication())
        }
    }

    fun toggleTaskTimer(note: NoteEntity) {
        if (!note.isTask || (note.isCompleted && note.taskTimerStartedAt == null)) return
        val now = System.currentTimeMillis()
        val timer = TaskTimer.toggle(note.taskElapsedMillis, note.taskTimerStartedAt, now)
        viewModelScope.launch {
            noteDao.update(
                note.copy(
                    taskElapsedMillis = timer.elapsedMillis,
                    taskTimerStartedAt = timer.startedAt,
                    updatedAt = now,
                    dirty = true
                )
            )
        }
    }

    companion object {
        /** Экранирование служебных символов LIKE (при ESCAPE '\\'). */
        internal fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun buildLockedTasksQuery(
            unlockedNotebookIds: Set<Long> = emptySet(),
            unlockedTaskIds: Set<String> = emptySet()
        ): SupportSQLiteQuery {
            val sql = StringBuilder(
                "SELECT notes.* FROM notes WHERE notes.isDeleted = 0" +
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
        fun buildQuery(
            search: String,
            filter: NoteFilter,
            sort: NoteSort,
            unlockedNotebookIds: Set<Long> = emptySet(),
            unlockedTaskIds: Set<String> = emptySet()
        ): SupportSQLiteQuery {
            val sql = StringBuilder("SELECT notes.* FROM notes WHERE notes.isDeleted = 0")
            val args = mutableListOf<Any>()
            when (filter) {
                NoteFilter.All -> Unit
                NoteFilter.Tasks -> sql.append(" AND notes.isTask = 1")
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
                sql.append(" OR notes.syncId IN (${unlockedTaskIds.joinToString(",") { "?" }})")
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
            sql.append(
                if (sort == NoteSort.BY_DATE) " ORDER BY notes.updatedAt DESC"
                else " ORDER BY notes.title COLLATE NOCASE ASC"
            )
            return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
        }
    }
}

private fun NotebookEntity.passwordRecord(): PasswordRecord? {
    if (!isProtected || passwordHash == null || passwordSalt == null) return null
    return PasswordRecord(passwordHash, passwordSalt)
}
