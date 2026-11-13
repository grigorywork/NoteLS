package com.notessync.app.ui.notes

import com.notessync.app.i18n.uiText
import com.notessync.app.audio.*
import com.notessync.app.notes.actions.NoteActions
import kotlinx.coroutines.flow.combine
import com.notessync.app.notes.text.*
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.photos.PhotoDisplaySize
import androidx.room.withTransaction
import com.notessync.app.notes.history.*
import com.notessync.app.settings.SettingsRepository
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.ui.notes.md.MarkdownEdit
import com.notessync.app.ui.tasks.DEFAULT_TASK_COLOR
import com.notessync.app.ui.tasks.TaskTimer
import com.notessync.app.widget.CalendarWidgetProvider
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.PasswordRecord
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteEditorViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    audioDependencies: EditorAudioDependencies? = null
) : AndroidViewModel(application) {

    private val app = application
    private val automaticPause = com.notessync.app.sync.auto.AutomaticSyncRuntime.existing(app)?.pauseManual()
    private val db = AppDatabase.get(application)
    private val noteDao = db.noteDao()
    private val tagDao = db.tagDao()
    private val attachmentDao = db.attachmentDao()
    /** id заметки; -1 — новая, создаётся при первом сохранении. */
    var currentNoteId: Long = savedState.get<Long>("noteId") ?: -1L
        private set

    private var createdHere: Boolean = savedState["createdHere"] ?: false
    private var deleted = false
    var isReady by mutableStateOf(false)
        private set
    private val loaded get() = isReady
    var isClosing by mutableStateOf(false)
        private set
    private var lastSavedNote: NoteEntity? = null

    private val document = EditorDocument(SettingsRepository.get(application).state.value.editorHistory,
        canEdit = { loaded && !accessDenied && !deleted && !isClosing }, report = { message = it })
    var formatting: TextFormatting
        get() = document.formatting
        private set(value) { document.formatting = value }
    var tables: NoteTables?
        get() = document.tables
        private set(value) { document.tables = value }
    var titleField: TextFieldValue
        get() = document.titleField
        private set(value) { document.titleField = value }
    var contentField: TextFieldValue
        get() = document.contentField
        private set(value) { document.contentField = value }
    var historyEnabled: Boolean
        get() = document.historyEnabled
        private set(value) { document.historyEnabled = value }
    private var unsupportedTables: String
        get() = document.unsupportedTables
        set(value) { document.unsupportedTables = value }
    val title: String get() = document.title
    val hasTables: Boolean get() = document.hasTables
    val canUndo: Boolean get() = document.canUndo
    val canRedo: Boolean get() = document.canRedo
    val editorField: EditorField get() = document.editorField
    val historyRestoreVersion: Long get() = document.historyRestoreVersion
    var taskPriority by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private val saveMutex = Mutex()
    private val media = EditorMedia(app, db, savedState, viewModelScope, saveMutex,
        parent = object : EditorMediaParent {
            override val currentNoteId get() = this@NoteEditorViewModel.currentNoteId
            override val syncId get() = this@NoteEditorViewModel.syncId
            override val loaded get() = this@NoteEditorViewModel.loaded
            override val isReady get() = this@NoteEditorViewModel.isReady
            override val accessDenied get() = this@NoteEditorViewModel.accessDenied
            override val deleted get() = this@NoteEditorViewModel.deleted
            override val isClosing get() = this@NoteEditorViewModel.isClosing
            override val exists get() = this@NoteEditorViewModel.exists
            override suspend fun accessibleParent() = this@NoteEditorViewModel.accessibleParent()
            override suspend fun persistParent() { persistNow(force = true) }
            override suspend fun saveParent() { saveNow(force = true) }
            override fun report(message: EditorMessage) { this@NoteEditorViewModel.message = message }
        }, audioDependencies)
    val isPhotoBusy: Boolean get() = media.isPhotoBusy
    val audioPlayback: AudioPlayback get() = media.audioPlayback
    val audioCapture: AudioCapture get() = media.audioCapture
    private val timing = EditorTaskTiming(app, viewModelScope, task = { isTask }, completed = { isCompleted },
        removed = { deleted }, parentId = { currentNoteId }, titleForNotification = { notificationTitle() },
        persist = { saveNow(force = true) }, report = { message = it })
    var taskElapsedMillis: Long
        get() = timing.taskElapsedMillis
        set(value) { timing.taskElapsedMillis = value }
    var taskTimerStartedAt: Long?
        get() = timing.taskTimerStartedAt
        set(value) { timing.taskTimerStartedAt = value }
    var taskCountdownMillis: Long
        get() = timing.taskCountdownMillis
        set(value) { timing.taskCountdownMillis = value }
    var taskCountdownEndAt: Long?
        get() = timing.taskCountdownEndAt
        set(value) { timing.taskCountdownEndAt = value }
    var reminderAt: Long?
        get() = timing.reminderAt
        set(value) { timing.reminderAt = value }
    var isFavorite by mutableStateOf(false)
    var isTask by mutableStateOf(savedState.get<Boolean>("isTask") ?: false)
    var isCompleted by mutableStateOf(false)
    var noteColor by mutableStateOf("")
        private set
    var taskColor by mutableStateOf(DEFAULT_TASK_COLOR)
    var taskCompletedAt by mutableStateOf<Long?>(null)
    var isProtected by mutableStateOf(false)
    var taskDueAt by mutableStateOf<Long?>(null)
        private set
    var message by mutableStateOf<EditorMessage?>(null)
        private set
    var previewMode by mutableStateOf(false)
    var accessDenied by mutableStateOf(false)
        private set
    var notebookName by mutableStateOf<String?>(null)
    var noteTags by mutableStateOf<List<String>>(emptyList())

    private var notebookId: Long? = null
    // Новая запись из календаря сразу закрепляется за выбранным днём.
    private var dayKey: Long? = savedState.get<Long>("dayKey")?.takeIf { it != -1L }
    private var passwordHash: String? = null
    private var passwordSalt: String? = null
    private var createdAt: Long = 0L
    private var syncId: String = UUID.randomUUID().toString()

    private val _noteIdFlow = MutableStateFlow(currentNoteId)
    val attachments: StateFlow<List<AttachmentEntity>> = _noteIdFlow
        .flatMapLatest { id -> if (id <= 0) flowOf(emptyList()) else attachmentDao.observeByNoteId(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notebooks: StateFlow<List<NotebookEntity>> = db.notebookDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exists: Boolean
        get() = currentNoteId != -1L

    init {
        viewModelScope.launch {
            combine(audioCapture.state, audioPlayback.state, _noteIdFlow) { capture, playback, id -> (capture.busy || playback.active) to id }
                .distinctUntilChanged().flatMapLatest { (active, id) ->
                    if (!active || id < 0) flowOf(true)
                    else combine(noteDao.observeAccess(id), ProtectionSession.state) { access, session ->
                        access?.allowed(id, syncId, session) == true
                    }
                }.collect { allowed ->
                    if (!allowed) { audioCapture.cancel(); audioPlayback.stop() }
                }
        }
        viewModelScope.launch {
            SettingsRepository.get(app).state.map { it.editorHistory }.distinctUntilChanged().collect {
                historyEnabled = it
                if (!it) { document.clearHistory() }
            }
        }
        viewModelScope.launch {
            com.notessync.app.sync.core.SyncWorkGate.transfer(automatic = false) load@{
            if (currentNoteId != -1L) {
                noteDao.getById(currentNoteId)?.let { note ->
                    lastSavedNote = note
                    val notebook = note.notebookId?.let { db.notebookDao().getById(it) }
                    val notebookLocked = notebook?.let {
                        it.isProtected && !ProtectionSession.isUnlocked(it)
                    } == true
                    val noteLocked = note.isProtected && !ProtectionSession.isUnlocked(note)
                    if (note.isDeleted || notebookLocked || noteLocked) {
                        accessDenied = true
                        return@load
                    }
                    titleField = TextFieldValue(note.title, TextRange(note.title.length))
                    contentField = TextFieldValue(note.content, TextRange(note.content.length))
                    formatting = withContext(Dispatchers.Default) { TextFormatting.decode(note.textFormat, note.content.length) }
                    tables = withContext(Dispatchers.Default) { NoteTables.decode(note.tablesData) }
                    unsupportedTables = if (tables == null) note.tablesData else ""
                    taskPriority = note.taskPriority
                    isFavorite = note.isFavorite
                    isTask = note.isTask
                    isCompleted = note.isCompleted
                    noteColor = note.noteColor
                    taskColor = note.taskColor
                    taskElapsedMillis = note.taskElapsedMillis
                    taskTimerStartedAt = note.taskTimerStartedAt
                    taskCountdownMillis = note.taskCountdownMillis
                    taskCountdownEndAt = note.taskCountdownEndAt
                    taskCompletedAt = note.taskCompletedAt
                    isProtected = note.isProtected
                    passwordHash = note.passwordHash
                    passwordSalt = note.passwordSalt
                    taskDueAt = note.taskDueAt
                    reminderAt = note.reminderAt
                    notebookId = note.notebookId
                    dayKey = note.dayKey
                    notebookName = notebook?.name
                    createdAt = note.createdAt
                    syncId = note.syncId
                    noteTags = tagDao.getTagNamesForNote(note.id)
                }
            }
            isReady = true
            startAutosave()
            }
        }
    }

    private fun startAutosave() {
        viewModelScope.launch {
            snapshotFlow {
                AutosaveKey(
                    title, contentField.text, formatting, tables, taskPriority, isFavorite, reminderAt, taskDueAt,
                    isTask, isCompleted, taskColor, noteColor, taskElapsedMillis, taskTimerStartedAt,
                    taskCountdownMillis, taskCountdownEndAt, taskCompletedAt, isProtected
                )
            }
                .drop(1)
                .debounce(500)
                .collect {
                    try { saveNow() }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        message = EditorMessage.SAVE_FAILED
                    }
                }
        }
    }

    private data class AutosaveKey(
        val title: String,
        val content: String,
        val formatting: TextFormatting,
        val tables: NoteTables?,
        val taskPriority: Int,
        val favorite: Boolean,
        val reminderAt: Long?,
        val taskDueAt: Long?,
        val isTask: Boolean,
        val isCompleted: Boolean,
        val taskColor: String,
        val noteColor: String,
        val taskElapsedMillis: Long,
        val taskTimerStartedAt: Long?,
        val taskCountdownMillis: Long,
        val taskCountdownEndAt: Long?,
        val taskCompletedAt: Long?,
        val isProtected: Boolean
    )

    suspend fun saveNow(force: Boolean = false) = saveMutex.withLock {
        if (!loaded || accessDenied || deleted) return@withLock
        // An insert and its returned ID/cache update must finish together even if the caller exits.
        withContext(NonCancellable) { persistNow(force) }
    }

    private suspend fun persistNow(force: Boolean) {
        if (deleted) return
        if (!force && !exists && title.isBlank() && contentField.text.isBlank() && !hasTables) return

        val now = System.currentTimeMillis()
        val tablesSnapshot = tables
        val unknownSnapshot = unsupportedTables
        val baseNote = NoteEntity(
            id = if (exists) currentNoteId else 0,
            syncId = syncId,
            title = title.trim(),
            content = contentField.text,
            textFormat = formatting.encode(),
            taskPriority = taskPriority,
            notebookId = notebookId,
            isFavorite = isFavorite,
            isDeleted = false,
            createdAt = if (createdAt > 0) createdAt else now,
            reminderAt = reminderAt,
            taskDueAt = taskDueAt,
            dayKey = dayKey,
            isTask = isTask,
            isCompleted = isCompleted,
            taskColor = taskColor,
            noteColor = noteColor,
            taskElapsedMillis = taskElapsedMillis,
            taskTimerStartedAt = taskTimerStartedAt,
            taskCountdownMillis = taskCountdownMillis,
            taskCountdownEndAt = taskCountdownEndAt,
            taskCompletedAt = taskCompletedAt,
            isProtected = isProtected,
            passwordHash = passwordHash,
            passwordSalt = passwordSalt,
            updatedAt = now,
            dirty = true
        )
        val encodedTables = withContext(Dispatchers.Default) { tablesSnapshot?.encode() ?: unknownSnapshot }
        val didWrite = db.withTransaction {
            val fresh = if (exists) noteDao.getById(currentNoteId) else null
            if (exists && (fresh == null || fresh.isDeleted || fresh.isPurged || fresh.syncId != syncId)) { deleted = true; return@withTransaction false }
            if (fresh != null && !NoteActions(app, db).canAccess(fresh)) { accessDenied = true; return@withTransaction false }
            val note = baseNote.copy(tablesData = encodedTables, isPinned = fresh?.isPinned ?: false)
            val previous = lastSavedNote
            if (previous != null && note.copy(isPinned = previous.isPinned, updatedAt = previous.updatedAt, dirty = previous.dirty) == previous) return@withTransaction false
            if (exists) noteDao.update(note) else {
                val newId = noteDao.upsert(note)
                currentNoteId = newId
                createdHere = true
                savedState["noteId"] = newId
                savedState["createdHere"] = true
            }
            createdAt = note.createdAt
            lastSavedNote = note.copy(id = currentNoteId)
            true
        }
        if (!didWrite) return
        // Observers must not see the new ID before the insert transaction commits.
        _noteIdFlow.value = currentNoteId
        if (dayKey != null) CalendarWidgetProvider.refreshAll(app, force)
        BackupManager.noteChanged()
    }

    /**
     * Аудит 2026-11-11: сброс отложенного автосохранения при выходе. Раньше быстрый
     * ввод и мгновенный выход (в пределах 500 мс debounce) терял хвост правки,
     * потому что экран закрывался раньше срабатывания автосохранения.
     */
    suspend fun flushOnExit(): Boolean {
        isClosing = true
        return try {
            // Freeze edits while the final write is pending, so exit cannot drop a late edit.
            if (exists || title.isNotBlank() || contentField.text.isNotBlank() || hasTables) saveNow(force = true)
            discardIfEmptyOnExit()
            BackupManager.requestAutoBackup(app, afterNote = true)
            true
        } catch (error: Exception) {
            isClosing = false
            if (error is CancellationException) throw error
            message = EditorMessage.SAVE_FAILED
            false
        }
    }

    // ---------- Форматирование (Этап 3) ----------

    // Text and table commands share one bounded history owned by the document.
    fun onEditorFocus(field: EditorField) = document.onEditorFocus(field)
    fun onTitleChange(next: TextFieldValue) = document.onTitleChange(next)
    fun prepareClipboardEdit() = document.prepareClipboardEdit()
    fun onContentChange(newValue: TextFieldValue) = document.onContentChange(newValue)
    fun undo() = document.undo()
    fun redo() = document.redo()
    fun formatSelection(kind: FormatKind, value: String?) = document.formatSelection(kind, value)
    fun selectAllContent() = document.selectAllContent()
    fun toggleBold() = document.toggleBold()
    fun toggleItalic() = document.toggleItalic()
    fun toggleTextStyle(kind: FormatKind) = document.toggleTextStyle(kind)
    fun toggleHeading(level: Int = 1) = document.toggleHeading(level)
    fun toggleList(kind: MarkdownEdit.ListKind = MarkdownEdit.ListKind.BULLET) = document.toggleList(kind)
    fun toggleChecklist() = document.toggleChecklist()
    fun formatParagraph(kind: FormatKind, value: String) = document.formatParagraph(kind, value)
    fun changeIndent(delta: Int) = document.changeIndent(delta)
    fun toggleCheckboxAtLine(lineIndex: Int) = document.toggleCheckboxAtLine(lineIndex)
    fun addTable(rows: Int, columns: Int) = document.addTable(rows, columns)
    fun removeTable(id: String) = document.removeTable(id)
    fun focusTableCell(ref: TableCellRef) = document.focusTableCell(ref)
    fun editTableCell(ref: TableCellRef, value: String) = document.editTableCell(ref, value)
    fun onTableCellChange(ref: TableCellRef, before: TextFieldValue, next: TextFieldValue) = document.onTableCellChange(ref, before, next)
    fun resizeTableColumn(id: String, column: Int, width: Int) = document.resizeTableColumn(id, column, width)
    fun resizeTableRow(id: String, row: Int, height: Int) = document.resizeTableRow(id, row, height)
    fun insertVoiceText(raw: String, structure: com.notessync.app.settings.VoiceStructure) = document.insertVoiceText(raw, structure)
    val activeFormatting: Map<FormatKind, String?> get() = document.activeFormatting
    val focusedTableCell: TableCellRef? get() = document.focusedTableCell
    val focusedTableSelection: EditorSelection? get() = document.focusedTableSelection

    suspend fun captureTransformSource(): com.notessync.app.ai.EditorTransformSource {
        if (!loaded || isClosing || accessDenied || deleted) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.ACCESS)
        val preview = contentField.text
        val selection = contentField.selection
        val length = if (selection.collapsed) preview.length else selection.max - selection.min
        if (length > com.notessync.app.ai.TransformLimits.INPUT) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.TOO_LARGE)
        com.notessync.app.ai.TransformLimits.input(if (selection.collapsed) preview else preview.substring(selection.min, selection.max))
        val settings = SettingsRepository.get(app).state.value
        if (!settings.aiEnabled) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.DISABLED)
        if (exists) {
            val access = noteDao.getTransformAccess(currentNoteId)
            if (access == null || access.syncId != syncId || !access.allowed(ProtectionSession.state.value)) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.ACCESS)
            if (lastSavedNote?.updatedAt != access.updatedAt) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.STALE)
        }
        saveNow(force = true)
        return saveMutex.withLock {
            val access = noteDao.getTransformAccess(currentNoteId)
                ?: throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.ACCESS)
            if (!access.allowed(ProtectionSession.state.value) || access.syncId != syncId) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.ACCESS)
            if (lastSavedNote?.updatedAt != access.updatedAt || lastSavedNote?.content != contentField.text) throw com.notessync.app.ai.TransformException(com.notessync.app.ai.TransformProblem.STALE)
            val body = contentField.text
            var start = if (contentField.selection.collapsed) 0 else contentField.selection.min.coerceIn(0, body.length)
            var end = if (contentField.selection.collapsed) body.length else contentField.selection.max.coerceIn(start, body.length)
            if (start > 0 && start < body.length && body[start].isLowSurrogate() && body[start - 1].isHighSurrogate()) start--
            if (end > 0 && end < body.length && body[end - 1].isHighSurrogate() && body[end].isLowSurrogate()) end++
            com.notessync.app.ai.TransformLimits.input(body.substring(start, end))
            com.notessync.app.ai.EditorTransformSource(access, body, formatting, start, end)
        }
    }
    suspend fun transformSourceCurrent(source: com.notessync.app.ai.EditorTransformSource): Boolean {
        if (!loaded || isClosing || accessDenied || deleted || !SettingsRepository.get(app).state.value.aiEnabled ||
            currentNoteId != source.access.id || syncId != source.access.syncId || contentField.text !== source.body || formatting != source.formatting) return false
        val current = noteDao.getTransformAccess(currentNoteId) ?: return false
        return current == source.access && current.allowed(ProtectionSession.state.value)
    }
    fun observeTransformAccess(id: Long) = noteDao.observeTransformAccess(id)
    suspend fun applyTransform(source: com.notessync.app.ai.EditorTransformSource, result: String): Boolean {
        com.notessync.app.ai.TransformLimits.output(result)
        if (!transformSourceCurrent(source)) return false
        // One atomic text/formatting history entry. Metadata and attachments stay outside this edit.
        document.replaceRange(source.body, source.start, source.end, result)
        try { saveNow(force = true) }
        catch (error: Exception) { if (error is CancellationException) throw error; message = EditorMessage.SAVE_FAILED }
        return !accessDenied && !deleted
    }

    fun chooseTaskDeadline(at: Long?) {
        if (!isReady || isClosing || accessDenied || !isTask || !com.notessync.app.ui.tasks.TaskDeadlines.valid(at) || taskDueAt == at) return
        taskDueAt = at
        viewModelScope.launch {
            try { saveNow(force = true) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message = EditorMessage.SAVE_FAILED
            }
        }
    }

    fun choosePriority(value: Int) {
        if (!isTask) return
        val next = com.notessync.app.ui.tasks.TaskPriority.from(value).value
        if (next == taskPriority) return
        taskPriority = next; viewModelScope.launch { saveNow(force = true) }
    }

    /** Тап по строке предпросмотра: переключить чек-бокс, если он там есть. */
    // ---------- Организация (Этап 2) ----------

    fun toggleFavorite() { isFavorite = !isFavorite }

    fun toggleTask() {
        isTask = !isTask
        if (!isTask) {
            taskPriority = 0
            taskDueAt = null
            isCompleted = false
            taskCompletedAt = null
            taskElapsedMillis = 0L
            taskTimerStartedAt = null
            cancelCountdownAlarm()
            taskCountdownMillis = 0L
            taskCountdownEndAt = null
            isProtected = false
            passwordHash = null
            passwordSalt = null
            ProtectionSession.lockTask(syncId)
        }
    }

    fun toggleCompleted() {
        if (!isTask) return
        isCompleted = !isCompleted
        taskCompletedAt = if (isCompleted) System.currentTimeMillis() else null
        if (isCompleted) {
            val timer = TaskTimer.pause(
                taskElapsedMillis,
                taskTimerStartedAt,
                System.currentTimeMillis()
            )
            taskElapsedMillis = timer.elapsedMillis
            taskTimerStartedAt = null
            // Этап 30: остаток таймера фиксируем, будильник снимаем.
            taskCountdownMillis = TaskTimer.countdownRemaining(
                taskCountdownMillis, taskCountdownEndAt, System.currentTimeMillis()
            )
            taskCountdownEndAt = null
            cancelCountdownAlarm()
            reminderAt = null
            if (exists) ReminderScheduler.cancel(app, currentNoteId)
        }
    }

    fun chooseTaskColor(color: String) {
        if (isTask) taskColor = color else noteColor = color
    }

    fun toggleTaskTimer() = timing.toggleTaskTimer()
    fun resetTaskTimer() = timing.resetTaskTimer()
    fun countdownRemainingAt(now: Long = System.currentTimeMillis()) = timing.countdownRemainingAt(now)
    fun countdownRunning() = timing.countdownRunning()
    fun countdownFinishedNow(now: Long = System.currentTimeMillis()) = timing.countdownFinishedNow(now)
    fun startCountdownUntil(target: Long) = timing.startCountdownUntil(target)
    fun startCountdown(durationMillis: Long) = timing.startCountdown(durationMillis)
    fun pauseCountdown() = timing.pauseCountdown()
    fun resumeCountdown() = timing.resumeCountdown()
    fun cancelCountdown() = timing.cancelCountdown()
    fun setReminder(at: Long) = timing.setReminder(at)
    fun reportReminderNotificationsDisabled() = timing.reportReminderNotificationsDisabled()
    fun clearReminder() = timing.clearReminder()
    private fun cancelCountdownAlarm() = timing.cancelCountdownAlarm()
    private suspend fun notificationTitle(): String {
        val notebookProtected = notebookId?.let { db.notebookDao().getById(it)?.isProtected } == true
        return if (isProtected || notebookProtected) uiText("Защищённая запись") else title.ifBlank { uiText("Без названия") }
    }

    fun protectTask(password: String) {
        if (!isTask) return
        viewModelScope.launch {
            val record = withContext(Dispatchers.Default) { PasswordProtector.create(password) }
            passwordHash = record.hash
            passwordSalt = record.salt
            isProtected = true
            saveNow(force = true)
            ProtectionSession.unlockTask(syncId)
        }
    }

    fun removeTaskProtection(password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val verified = verifyTaskPassword(password)
            if (verified) {
                isProtected = false
                passwordHash = null
                passwordSalt = null
                saveNow(force = true)
                ProtectionSession.lockTask(syncId)
            }
            onResult(verified)
        }
    }

    fun lockTask() {
        document.clearHistory()
        ProtectionSession.lockTask(syncId)
    }

    private suspend fun verifyTaskPassword(password: String): Boolean {
        val hash = passwordHash ?: return false
        val salt = passwordSalt ?: return false
        if (!isProtected) return false
        return withContext(Dispatchers.Default) {
            PasswordProtector.verify(password, PasswordRecord(hash, salt))
        }
    }

    fun clearMessage() {
        message = null
    }

    fun assignNotebook(notebook: NotebookEntity?) {
        notebookId = notebook?.id
        notebookName = notebook?.name
        viewModelScope.launch { saveNow() }
    }

    fun addTag(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || noteTags.any { it.equals(clean, ignoreCase = true) }) return
        viewModelScope.launch {
            saveNow()
            // Аудит 2026-11-11: один запрос вместо двух; при гонке вставки
            // (IGNORE вернул -1) идентификатор дочитываем.
            val tagId = tagDao.getByName(clean)?.id
                ?: tagDao.insert(TagEntity(name = clean)).takeIf { it > 0 }
                ?: tagDao.getByName(clean)?.id
                ?: return@launch
            tagDao.attachToNote(NoteTagCrossRef(noteId = currentNoteId, tagId = tagId))
            BackupManager.noteChanged()
            noteTags = tagDao.getTagNamesForNote(currentNoteId)
        }
    }

    fun removeTag(name: String) {
        viewModelScope.launch {
            val tag = tagDao.getByName(name) ?: return@launch
            tagDao.detachFromNote(currentNoteId, tag.id)
            BackupManager.noteChanged()
            noteTags = tagDao.getTagNamesForNote(currentNoteId)
        }
    }

    // ---------- Вложения (Этап 3) ----------

    fun startAudioRecording() = media.startAudioRecording()
    fun attach(uri: Uri) = media.attach(uri)
    suspend fun prepareCameraCapture(): Uri? = media.prepareCameraCapture()
    fun finishCameraCapture(success: Boolean) = media.finishCameraCapture(success)
    suspend fun savePhotoSize(photo: AttachmentEntity, requested: PhotoDisplaySize): Boolean = media.savePhotoSize(photo, requested)
    fun removeAttachment(attachment: AttachmentEntity) = media.removeAttachment(attachment)

    private suspend fun accessibleParent(): NoteEntity? {
        val current = noteDao.getById(currentNoteId) ?: return null
        return current.takeIf { it.syncId == syncId && !it.isDeleted && !it.isPurged && NoteActions(app, db).canAccess(it) }
    }
    // ---------- Напоминания (Этап 3) ----------

    // ---------- Удаление / выход ----------

    suspend fun discardIfEmptyOnExit() = saveMutex.withLock {
        if (loaded && createdHere && !deleted && !accessDenied && title.isBlank() && contentField.text.isBlank() && !hasTables && reminderAt == null && taskDueAt == null && exists) {
            withContext(NonCancellable) {
                db.withTransaction {
                    val fresh = accessibleParent() ?: return@withTransaction
                    if (fresh.title.isBlank() && fresh.content.isBlank() && fresh.tablesData.isBlank() && fresh.reminderAt == null && fresh.taskDueAt == null &&
                        attachmentDao.countByNoteId(currentNoteId) == 0) {
                        noteDao.softDelete(currentNoteId); deleted = true
                    }
                }
            }
        }
        document.clearHistory()
    }

    suspend fun deleteNow(): Long = saveMutex.withLock {
        withContext(NonCancellable) {
            val deletedId = if (!exists) -1L else db.withTransaction {
                if (accessibleParent() == null) return@withTransaction -1L
                val changed = NoteActions(app, db).apply(setOf(currentNoteId), NoteActions.Action.TRASH)
                if (changed > 0) currentNoteId else -1L
            }
            if (deletedId > 0 && dayKey != null) CalendarWidgetProvider.refreshAll(app)
            deleted = true
            document.clearHistory()
            deletedId
        }
    }

    override fun onCleared() {
        com.notessync.app.sync.core.SyncWorkGate.releaseAfter(automaticPause) { saveMutex.withLock { } }
        media.close()
        document.releaseHistory()
        super.onCleared()
    }
}
