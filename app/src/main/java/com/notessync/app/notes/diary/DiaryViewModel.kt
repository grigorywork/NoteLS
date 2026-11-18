package com.notessync.app.notes.diary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.model.NoteIdentity
import com.notessync.app.data.local.model.passwordRecord
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.security.*
import com.notessync.app.security.biometrics.AndroidNotebookBiometricVault
import com.notessync.app.security.biometrics.NotebookBiometricController
import com.notessync.app.widget.CalendarWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DiaryRows(val book: NotebookEntity, val trash: Boolean, val page: Int, val rows: List<DiaryRow>)
internal data class DiaryCandidates(val book: NotebookEntity, val access: ProtectionState, val page: Int, val rows: List<DiaryCandidate>, val includeTasks: Boolean)
private data class DiaryCandidateKey(val book: NotebookEntity, val access: ProtectionState, val page: Int, val includeTasks: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val selected = MutableStateFlow<Long?>(null)
    val books = db.notebookDao().observeDiaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)
    internal val book = combine(books, selected) { books, id -> books?.firstOrNull { it.id == id } ?: books?.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)
    private val controller = NotebookBiometricController(viewModelScope, db, AndroidNotebookBiometricVault(application))
    private val operation = MutableStateFlow(false)
    val busy = combine(operation, controller.busy) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), false)
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableTrash = MutableStateFlow(false)
    val trash = mutableTrash.asStateFlow()
    private val limit = MutableStateFlow(0)
    val page = limit.asStateFlow()
    private val candidateLimit = MutableStateFlow(0)
    val candidatePage = candidateLimit.asStateFlow()
    private val migration = MutableStateFlow(false)
    private val datedTasks = MutableStateFlow(false)
    val includeDatedTasks = datedTasks.asStateFlow()
    @Volatile private var generation = 0L
    internal val rows = combine(book, ProtectionSession.state, mutableTrash, limit) { book, access, trash, limit ->
        if (book != null && DiaryPrivacy.valid(book) && book.id in access.notebookIds) Triple(book, trash, limit) else null
    }.flatMapLatest { key -> if (key == null) flowOf(null) else db.noteDao().diaryRows(PrivateDiary.rows(key.first.id, key.second, key.third))
        .map { DiaryRows(key.first, key.second, key.third, it) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)
    internal val candidates = combine(book, ProtectionSession.state, migration, candidateLimit, datedTasks) { book, access, open, page, tasks ->
        if (open && book != null && DiaryPrivacy.valid(book) && book.id in access.notebookIds) DiaryCandidateKey(book, access, page, tasks) else null
    }.flatMapLatest { key -> if (key == null) flowOf(null) else db.noteDao().diaryCandidates(PrivateDiary.candidates(key.access.notebookIds, key.page, key.access.taskSyncIds, key.includeTasks))
        .map { DiaryCandidates(key.book, key.access, key.page, it, key.includeTasks) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)

    private fun occupied() = operation.value || controller.busy.value
    fun clearMessage() { mutableMessage.value = null }
    fun select(id: Long) { lock(); selected.value = id; mutableTrash.value = false; limit.value = 0 }
    fun showTrash(value: Boolean) { mutableTrash.value = value; limit.value = 0 }
    fun showMigration(value: Boolean) { migration.value = value; candidateLimit.value = 0; datedTasks.value = false }
    fun showDatedTasks(value: Boolean) { datedTasks.value = value; candidateLimit.value = 0 }
    fun more() { limit.value = (limit.value.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    fun moreCandidates() { candidateLimit.value = (candidateLimit.value.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    fun previous() { limit.value = (limit.value - 1).coerceAtLeast(0) }
    fun previousCandidates() { candidateLimit.value = (candidateLimit.value - 1).coerceAtLeast(0) }
    fun lock() { stopped(); (book.value?.id ?: selected.value)?.let(DiarySession::lock) }
    fun stopped() { generation++; controller.cancel(); migration.value = false }

    fun setup(password: String, confirmation: String) {
        if (occupied()) return
        if (password != confirmation || password.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            mutableMessage.value = "Пароли должны совпадать: 4–128 символов"; return
        }
        val token = generation; val epoch = DiarySession.generation
        operation.value = true
        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.Default) { PasswordProtector.create(password) }
                if (token != generation || epoch != DiarySession.generation) return@launch
                val created = db.withTransaction {
                    check(db.notebookDao().getDiaries().isEmpty())
                    PrivateDiary.create(db, record)
                }
                selected.value = created.id
                if (token == generation && epoch == DiarySession.generation) {
                    DiarySession.register(created.id); ProtectionSession.unlockNotebook(created.id)
                }
            } catch (error: Exception) { failed(error) }
            finally { operation.value = false }
        }
    }

    fun unlock(password: String) {
        if (occupied()) return
        val expected = book.value ?: return
        if (!DiaryPrivacy.valid(expected)) { mutableMessage.value = "Не удалось открыть дневник. Повторите."; return }
        selected.value = expected.id
        controller.unlockPassword(expected, password) { accepted -> if (!accepted) mutableMessage.value = "Неверный пароль или запись изменилась" }
    }

    fun changePassword(old: String, replacement: String, confirmation: String) {
        if (occupied()) return
        if (replacement != confirmation || replacement.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            mutableMessage.value = "Пароли должны совпадать: 4–128 символов"; return
        }
        val expected = book.value ?: return
        val token = generation; val epoch = DiarySession.generation
        operation.value = true
        viewModelScope.launch {
            try {
                val changed = PrivateDiary.changePassword(db, expected, old, replacement) {
                    token == generation && epoch == DiarySession.generation
                }
                if (token == generation) mutableMessage.value = if (changed) "Пароль дневника изменён" else "Неверный пароль или запись изменилась"
            } catch (error: Exception) { failed(error) }
            finally { operation.value = false }
        }
    }

    fun createEntry(onOpen: (Long) -> Unit) {
        if (occupied()) return
        val expected = book.value ?: return
        val token = generation; val epoch = DiarySession.generation
        operation.value = true
        viewModelScope.launch {
            try {
                val note = DiaryEntries.create(db, expected)
                afterChange()
                if (token == generation && epoch == DiarySession.generation && ProtectionSession.isUnlocked(expected)) onOpen(note.id)
            } catch (error: Exception) { failed(error) }
            finally { operation.value = false }
        }
    }

    fun move(expected: NotebookEntity, identities: List<NoteIdentity>, includeTasks: Boolean = false) {
        if (occupied()) return
        operation.value = true
        viewModelScope.launch {
            try {
                val count = PrivateDiary.move(db, expected, identities, includeTasks)
                afterChange()
                runCatching { com.notessync.app.reminders.AlarmReconciler.refreshNotes(getApplication(), db, identities.mapTo(mutableSetOf()) { it.id }) }
                mutableMessage.value = if (count == identities.size) "Записи перенесены в дневник" else "Часть записей изменилась или закрыта. Повторите перенос."
                migration.value = false
            } catch (error: Exception) { failed(error) }
            finally { operation.value = false }
        }
    }

    fun action(row: DiaryRow, restore: Boolean) {
        if (occupied()) return
        val expected = book.value ?: return
        operation.value = true
        viewModelScope.launch {
            try {
                val action = if (restore) NoteActions.Action.RESTORE else NoteActions.Action.TRASH
                NoteActions(getApplication(), db).apply(setOf(row.id), action, mapOf(row.id to row.syncId), expected.id)
                afterChange()
            } catch (error: Exception) { failed(error) }
            finally { operation.value = false }
        }
    }

    /** Imported entries can retain a second, independent task password; no title is exposed first. */
    fun openProtected(row: DiaryRow, password: String, onOpen: (Long) -> Unit) {
        if (occupied()) return
        val expected = book.value ?: return
        val token = generation; val epoch = DiarySession.generation
        operation.value = true
        viewModelScope.launch {
            try {
                val binding = db.noteDao().getTaskProtection(row.id)
                val record = binding?.passwordRecord()
                val verified = binding != null && binding.syncId == row.syncId && binding.isDeleted == row.isDeleted && !binding.isPurged && record != null &&
                    withContext(Dispatchers.Default) { PasswordProtector.verify(password, record) }
                val accepted = db.withTransaction {
                    val parent = db.notebookDao().getById(expected.id)
                    val access = db.noteDao().getAccess(row.id)
                    val unchanged = db.noteDao().getTaskProtection(row.id) == binding
                    withContext(Dispatchers.Main.immediate) {
                        val valid = verified && parent == expected && DiaryPrivacy.valid(expected) && ProtectionSession.isUnlocked(expected) &&
                            access?.notebookId == expected.id && unchanged && token == generation && epoch == DiarySession.generation
                        if (valid) ProtectionSession.unlockTask(row.syncId)
                        valid
                    }
                }
                if (token == generation && epoch == DiarySession.generation) {
                    if (accepted) { operation.value = false; onOpen(row.id) }
                    else mutableMessage.value = "Неверный пароль или запись изменилась"
                }
            } catch (error: Exception) { failed(error) }
            finally { operation.value = false }
        }
    }

    private fun afterChange() {
        BackupManager.noteChanged()
        runCatching { BackupManager.requestAutoBackup(getApplication(), afterNote = true) }
        runCatching { CalendarWidgetProvider.refreshAll(getApplication()) }
    }
    private fun failed(error: Exception) {
        if (error is CancellationException) throw error
        mutableMessage.value = "Не удалось выполнить действие. Повторите."
    }
    override fun onCleared() { lock() }
}
