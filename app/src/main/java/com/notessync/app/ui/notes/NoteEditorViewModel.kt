package com.notessync.app.ui.notes

import com.notessync.app.data.local.model.nextChangeTimestamp

import com.notessync.app.i18n.uiText
import com.notessync.app.audio.*
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.notes.NotebookTarget
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
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.ui.notes.md.MarkdownEdit
import com.notessync.app.ui.tasks.DEFAULT_TASK_COLOR
import com.notessync.app.ui.tasks.TaskTimer
import com.notessync.app.ui.tasks.toOrdinaryNote
import com.notessync.app.widget.CalendarWidgetProvider
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.PasswordRecord
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import com.notessync.app.ui.tasks.withTaskMetadata
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
    private var lastSavedSubtasks: List<com.notessync.app.notes.subtasks.Subtask> = emptyList()
    val subtasks get() = document.subtasks
    val hasSubtasks get() = subtasks.isNotEmpty()

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
            override suspend fun accessibleParent() = noteDao.getTransformAccess(currentNoteId)?.takeIf {
                it.syncId == syncId && it.allowed(ProtectionSession.state.value)
            }
            override fun metadataChanged(beforeVersion: Long, version: Long) = adoptMetadataVersion(beforeVersion, version)
            override suspend fun persistParent() { persistNow(force = true) }
            override suspend fun saveParent() { saveNow(force = true) }
            override fun report(message: EditorMessage) { this@NoteEditorViewModel.message = message }
        }, audioDependencies)
    val isPhotoBusy: Boolean get() = media.isPhotoBusy
    val audioPlayback: AudioPlayback get() = media.audioPlayback
    val audioCapture: AudioCapture get() = media.audioCapture
    private val timing = EditorTaskTiming(app, viewModelScope, task = { isTask }, completed = { isCompleted },
        removed = { deleted || accessDenied }, parentId = { currentNoteId },
        persist = { saveNow(force = true) }, report = { message = it }, parentSyncId = { syncId }, acceptsNew = { !isClosing })
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

    var isDiaryEntry by mutableStateOf(false)
        private set
    private val accessLoaded = MutableStateFlow(false)
    private var notebookId: Long? = null
    private var notebookTarget: NotebookTarget? = null
    private var notebookChoiceDirty = false
    private var notebookChoiceVersion = 0L
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

    val notebooks: StateFlow<List<NotebookEntity>> = db.notebookDao().observeAll().map { books -> books.filterNot { it.isDiary } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val noteIdentity get() = com.notessync.app.data.local.model.NoteIdentity(currentNoteId, syncId)
    val exists: Boolean
        get() = currentNoteId != -1L

    init {
        viewModelScope.launch {
            val accessFlow = _noteIdFlow.flatMapLatest { id -> if (id > 0) noteDao.observeAccess(id) else flowOf(null) }
            combine(accessFlow, ProtectionSession.state, accessLoaded) { access, session, ready -> Triple(access, session, ready) }
                .collect { (access, session, ready) ->
                    if (ready && (isDiaryEntry || access?.notebookDiary == true)) {
                        isDiaryEntry = true
                        if (access?.allowed(currentNoteId, syncId, session) != true) {
                            // Never save cleared presentation state; the committed document stays intact.
                            accessDenied = true
                            audioCapture.cancel(); audioPlayback.stop()
                            titleField = TextFieldValue(""); contentField = TextFieldValue("")
                            formatting = TextFormatting(); tables = null; unsupportedTables = ""
                            document.subtasks = emptyList(); lastSavedSubtasks = emptyList()
                            noteTags = emptyList(); notebookName = null; document.clearHistory()
                        }
                    }
                }
        }
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
                    isDiaryEntry = notebook?.isDiary == true
                    val notebookLocked = notebook?.let {
                        (it.isDiary && !com.notessync.app.notes.diary.DiaryPrivacy.valid(it)) || (it.isProtected && !ProtectionSession.isUnlocked(it))
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
                    document.subtasks = com.notessync.app.notes.subtasks.Subtasks.checked(db.subtaskDao().get(note.id).map { it.item() })
                    lastSavedSubtasks = document.subtasks
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
                    notebookTarget = notebook?.let { NotebookTarget(it.id, it.syncId) }
                    dayKey = note.dayKey
                    notebookName = notebook?.name
                    com.notessync.app.data.subtasks.replaceSubtasks(db, currentNoteId, committedSubtasks)
            createdAt = note.createdAt
                    syncId = note.syncId
                    noteTags = tagDao.getTagNamesForNote(note.id)
                }
            }
            if (currentNoteId == -1L) {
                val parentId = savedState.get<Long>("notebookId")?.takeIf { it > 0 }
                if (parentId != null) {
                    val target = NotebookTarget(parentId, savedState.get<String>("notebookSyncId").orEmpty())
                    val book = db.notebookDao().getById(parentId)
                    if (!target.allows(book, ProtectionSession.state.value)) {
                        accessDenied = true
                        return@load
                    }
                    notebookId = book!!.id; notebookName = book.name; notebookTarget = target
                    notebookChoiceDirty = true
                }
            }
            isReady = true
            accessLoaded.value = true
            startAutosave()
            startTaskMetadataObserver()
            startSubtaskObserver()
            }
        }
    }

    private fun adoptTaskTiming(expected: NoteEntity, value: NoteEntity) {
        if (taskElapsedMillis == expected.taskElapsedMillis && taskTimerStartedAt == expected.taskTimerStartedAt) {
            taskElapsedMillis = value.taskElapsedMillis; taskTimerStartedAt = value.taskTimerStartedAt
        }
        if (taskCountdownMillis == expected.taskCountdownMillis && taskCountdownEndAt == expected.taskCountdownEndAt) {
            taskCountdownMillis = value.taskCountdownMillis; taskCountdownEndAt = value.taskCountdownEndAt
        }
        if (isCompleted == expected.isCompleted && taskCompletedAt == expected.taskCompletedAt) {
            isCompleted = value.isCompleted; taskCompletedAt = value.taskCompletedAt
        }
        if (reminderAt == expected.reminderAt) reminderAt = value.reminderAt
    }

    private fun startTaskMetadataObserver() { viewModelScope.launch {
        try {
            combine(_noteIdFlow, snapshotFlow { isTask }) { id, task -> if (task) id else -1L }
                .distinctUntilChanged().flatMapLatest { id -> if (id > 0) noteDao.observeTaskMetadata(id) else kotlinx.coroutines.flow.flowOf(null) }
                .distinctUntilChanged().collectLatest { signal ->
                    if (signal == null) return@collectLatest
                    protection.awaitIdle()
                    saveMutex.withLock {
                        if (!isReady || isClosing || deleted || accessDenied || !isTask) return@withLock
                        val id = currentNoteId
                        val fresh = db.withTransaction {
                            val access = noteDao.getAccess(id) ?: return@withTransaction null
                            if (!access.allowed(id, syncId, ProtectionSession.state.value)) return@withTransaction null
                            val book = access.notebookId?.let { db.notebookDao().getById(it) }
                            if ((access.notebookId != null && book == null) || book?.isDeleted == true || (book?.isDiary == true && !com.notessync.app.notes.diary.DiaryPrivacy.valid(book))) return@withTransaction null
                            noteDao.getTaskMetadata(id)?.takeIf { it.id == id && it.syncId == syncId && it.isTask }
                        } ?: return@withLock
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (!isReady || isClosing || deleted || accessDenied || !isTask) return@withLock
                        val previous = lastSavedNote?.takeIf { it.id == id && it.syncId == syncId && it.isTask } ?: return@withLock
                        val local = previous.copy(isTask = isTask, isCompleted = isCompleted, taskCompletedAt = taskCompletedAt,
                            taskElapsedMillis = taskElapsedMillis, taskTimerStartedAt = taskTimerStartedAt,
                            taskCountdownMillis = taskCountdownMillis, taskCountdownEndAt = taskCountdownEndAt, reminderAt = reminderAt)
                        val committed = previous.withTaskMetadata(fresh)
                        val merged = com.notessync.app.ui.tasks.mergeExternalTaskTiming(local, previous, committed)
                        // Cache only what Room committed; a local accepted control can still be waiting to save.
                        lastSavedNote = committed
                        adoptTaskTiming(local, merged)
                    }
                }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A failed read cannot overwrite the document. Save/reopen still uses fresh guards. */ }
    } }

    private fun startSubtaskObserver() { viewModelScope.launch {
        try {
            _noteIdFlow.flatMapLatest { id ->
                if (id > 0) db.subtaskDao().observe(id) else kotlinx.coroutines.flow.flowOf(emptyList())
            }.distinctUntilChanged().collectLatest {
                protection.awaitIdle()
                saveMutex.withLock {
                    if (!isReady || isClosing || deleted || accessDenied || currentNoteId <= 0) return@withLock
                    val committed = db.withTransaction {
                        val access = noteDao.getAccess(currentNoteId) ?: return@withTransaction null
                        if (!access.allowed(currentNoteId, syncId, ProtectionSession.state.value)) return@withTransaction null
                        val book = access.notebookId?.let { db.notebookDao().getById(it) }
                        if ((access.notebookId != null && book == null) || book?.isDeleted == true ||
                            (book?.isDiary == true && !com.notessync.app.notes.diary.DiaryPrivacy.valid(book))) return@withTransaction null
                        com.notessync.app.notes.subtasks.Subtasks.checked(db.subtaskDao().get(currentNoteId).map { row -> row.item() })
                    } ?: return@withLock
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (isClosing || accessDenied || deleted) return@withLock
                    document.subtasks = com.notessync.app.notes.subtasks.Subtasks.merge(subtasks, lastSavedSubtasks, committed)
                    lastSavedSubtasks = committed
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Save/reopen rechecks access and identity; never publish stale data. */ }
    } }

    private fun startAutosave() {
        viewModelScope.launch {
            snapshotFlow {
                AutosaveKey(
                    title, contentField.text, formatting, tables, subtasks, taskPriority, isFavorite, reminderAt, taskDueAt,
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
        val subtasks: List<com.notessync.app.notes.subtasks.Subtask>,
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

    suspend fun saveNow(force: Boolean = false): Boolean {
        protection.awaitIdle()
        return saveDocument(force)
    }
    private suspend fun saveDocument(force: Boolean): Boolean = saveMutex.withLock {
        if (!loaded || accessDenied || deleted) return@withLock false
        // An insert and its returned ID/cache update must finish together even if the caller exits.
        withContext(NonCancellable) { persistNow(force) }
    }

    private suspend fun persistNow(force: Boolean): Boolean {
        if (deleted) return false
        if (!force && !exists && title.isBlank() && contentField.text.isBlank() && !hasTables && !hasSubtasks) return true

        val now = System.currentTimeMillis()
        val submittedSubtasks = subtasks
        var committedSubtasks = lastSavedSubtasks
        var subtaskAlarmChanged = false
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
        val chosenTarget = notebookTarget
        val choiceDirty = notebookChoiceDirty
        val choiceVersion = notebookChoiceVersion
        val encodedTables = withContext(Dispatchers.Default) { tablesSnapshot?.encode() ?: unknownSnapshot }
        var accepted = false
        var acceptedNotebook: NotebookEntity? = null
        val didWrite = db.withTransaction {
            val fresh = if (exists) noteDao.getById(currentNoteId) else null
            if (exists && (fresh == null || fresh.isDeleted || fresh.isPurged || fresh.syncId != syncId)) { deleted = true; return@withTransaction false }
            if (fresh != null && !NoteActions(app, db).canAccess(fresh)) { accessDenied = true; return@withTransaction false }
            val freshBook = fresh?.notebookId?.let { db.notebookDao().getById(it) }
            val privateParent = freshBook?.takeIf { it.isDiary }
            // An external move/deletion wins over stale editor metadata, unless the user chose a destination.
            // A diary cannot be moved out through a stale ordinary-editor choice.
            val useFreshParent = fresh != null && (!choiceDirty || privateParent != null)
            val targetId = if (useFreshParent) fresh?.notebookId else baseNote.notebookId
            val targetBook = if (useFreshParent) freshBook else targetId?.let { db.notebookDao().getById(it) }
            val targetIdentity = if (useFreshParent) targetBook?.let { NotebookTarget(it.id, it.syncId) } else chosenTarget
            if (targetId != null && (targetIdentity == null || !targetIdentity.allows(targetBook,
                    ProtectionSession.state.value, allowDiary = privateParent?.id == targetId))) {
                accessDenied = true
                return@withTransaction false
            }
            accepted = true; acceptedNotebook = targetBook
            val local = baseNote.copy(tablesData = encodedTables, isPinned = fresh?.isPinned ?: false,
                notebookId = targetId)
            val previous = lastSavedNote
            val merged = com.notessync.app.ui.tasks.mergeExternalTaskTiming(local, previous, fresh)
            val note = if (fresh == null) merged else merged.copy(isProtected = fresh.isProtected,
                passwordHash = fresh.passwordHash, passwordSalt = fresh.passwordSalt, updatedAt = nextChangeTimestamp(fresh.updatedAt, now))
            val freshSubtasks = if (exists) db.subtaskDao().get(currentNoteId).map { it.item() } else emptyList()
            val mergedSubtasks = com.notessync.app.notes.subtasks.Subtasks.merge(submittedSubtasks, lastSavedSubtasks, freshSubtasks)
                .let { rows -> if (note.isTask && note.isCompleted) rows.map { it.paused(now) } else rows }
            val subtaskChanges = mergedSubtasks != freshSubtasks
            subtaskAlarmChanged = freshSubtasks.map { it.copy(text = "", elapsedMillis = 0, startedAt = null) } !=
                mergedSubtasks.map { it.copy(text = "", elapsedMillis = 0, startedAt = null) } ||
                (freshSubtasks.isNotEmpty() && note.isTask && note.isCompleted && fresh?.isCompleted != true)
            committedSubtasks = mergedSubtasks
            if (!subtaskChanges && previous != null && fresh?.notebookId == local.notebookId && local.copy(isPinned = previous.isPinned, isProtected = previous.isProtected,
                    passwordHash = previous.passwordHash, passwordSalt = previous.passwordSalt,
                    updatedAt = previous.updatedAt, dirty = previous.dirty) == previous) {
                lastSavedNote = com.notessync.app.ui.tasks.mergeExternalTaskTiming(previous, previous, fresh).copy(
                    isProtected = note.isProtected, passwordHash = note.passwordHash, passwordSalt = note.passwordSalt)
                return@withTransaction false
            }
            if (subtaskAlarmChanged) com.notessync.app.reminders.AlarmReconciler.requireRecovery(app)
            if ((fresh?.reminderAt != note.reminderAt || fresh?.taskCountdownEndAt != note.taskCountdownEndAt ||
                    fresh?.isTask != note.isTask || fresh?.isCompleted != note.isCompleted) &&
                (fresh?.reminderAt != null || fresh?.taskCountdownEndAt != null || note.reminderAt != null || note.taskCountdownEndAt != null)) {
                com.notessync.app.reminders.AlarmReconciler.requireRecovery(app)
            }
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
        if (accepted) {
            document.subtasks = com.notessync.app.notes.subtasks.Subtasks.merge(subtasks, submittedSubtasks, committedSubtasks)
            lastSavedSubtasks = committedSubtasks
        }
        if (accepted) lastSavedNote?.let { isProtected = it.isProtected; passwordHash = it.passwordHash; passwordSalt = it.passwordSalt }
        // A notification command may have changed timing while this editor was open.
        // Do not clobber a newer UI gesture that arrived while the transaction was saving.
        if (accepted) lastSavedNote?.let { adoptTaskTiming(baseNote, it) }
        if (accepted && notebookChoiceVersion == choiceVersion) {
            notebookId = acceptedNotebook?.id; notebookName = acceptedNotebook?.name
            notebookTarget = acceptedNotebook?.let { NotebookTarget(it.id, it.syncId) }
            notebookChoiceDirty = false
            if (acceptedNotebook?.isDiary == true) isDiaryEntry = true
        }
        if (!didWrite) return accepted
        // Observers must not see the new ID before the insert transaction commits.
        _noteIdFlow.value = currentNoteId
        if (dayKey != null) CalendarWidgetProvider.refreshAll(app, force)
        if (subtaskAlarmChanged) runCatching { com.notessync.app.data.subtasks.SubtaskAlarms.reconcile(app, db) }
        BackupManager.noteChanged()
        return true
    }

    /**
     * Аудит 2026-11-11: сброс отложенного автосохранения при выходе. Раньше быстрый
     * ввод и мгновенный выход (в пределах 500 мс debounce) терял хвост правки,
     * потому что экран закрывался раньше срабатывания автосохранения.
     */
    suspend fun flushOnExit(): Boolean {
        isClosing = true
        return try {
            protection.awaitIdle()
            // Freeze edits while the final write is pending, so exit cannot drop a late edit.
            if (exists || title.isNotBlank() || contentField.text.isNotBlank() || hasTables || hasSubtasks) saveNow(force = true)
            timing.awaitIdle()
            tags.awaitIdle()
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

    fun addSubtask() = document.addSubtask()
    fun editSubtask(id: String, text: String) = document.editSubtask(id, text)
    fun removeSubtask(id: String) = document.removeSubtask(id)
    fun toggleSubtask(id: String) = document.toggleSubtask(id, System.currentTimeMillis())
    fun toggleSubtaskStopwatch(id: String) = document.changeSubtaskClock(id) { row ->
        if (isTask && isCompleted) return@changeSubtaskClock row
        val now = System.currentTimeMillis()
        if (row.startedAt == null) row.copy(startedAt = now) else row.copy(
            elapsedMillis = com.notessync.app.tasks.running.RunningClockTime.elapsed(row.elapsedMillis, row.startedAt, now), startedAt = null)
    }
    fun startSubtaskCountdown(id: String, millis: Long) {
        if ((isTask && isCompleted) || millis !in 1..com.notessync.app.tasks.running.RunningClockTime.MAX_NATIVE_MILLIS) return
        document.changeSubtaskClock(id) { it.copy(countdownMillis = millis, countdownFinishedAt = null,
            countdownEndAt = System.currentTimeMillis().let { now -> if (millis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + millis }) }
    }
    fun pauseSubtaskCountdown(id: String) = document.changeSubtaskClock(id) { row ->
        row.copy(countdownMillis = row.countdownEndAt?.let { com.notessync.app.tasks.running.RunningClockTime.remaining(it, System.currentTimeMillis()) } ?: row.countdownMillis,
            countdownEndAt = null)
    }
    fun resumeSubtaskCountdown(id: String) { subtasks.find { it.id == id }?.let { startSubtaskCountdown(id, it.countdownMillis) } }
    fun clearSubtaskCountdown(id: String) = document.changeSubtaskClock(id) { it.copy(countdownMillis = 0, countdownEndAt = null, countdownFinishedAt = null) }

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
    fun toggleLocalizedList(kind: MarkdownEdit.ListKind) = document.toggleList(kind,
        if (com.notessync.app.i18n.UiStrings.language == com.notessync.app.i18n.UiLanguage.RUSSIAN)
            com.notessync.app.ui.notes.md.ListAlphabet.CYRILLIC else com.notessync.app.ui.notes.md.ListAlphabet.LATIN)
    fun toggleChecklist() = document.toggleChecklist()
    fun formatParagraph(kind: FormatKind, value: String) = document.formatParagraph(kind, value)
    fun changeIndent(delta: Int) = document.changeIndent(delta)
    fun toggleCheckboxAtLine(lineIndex: Int) = document.toggleCheckboxAtLine(lineIndex)
    fun addTable(rows: Int, columns: Int) = document.addTable(rows, columns)
    fun removeTable(id: String) = document.removeTable(id)
    fun focusTableCell(ref: TableCellRef) = document.focusTableCell(ref)
    fun editTableCell(ref: TableCellRef, value: String) = document.editTableCell(ref, value)
    fun onTableCellChange(ref: TableCellRef, before: TextFieldValue, next: TextFieldValue) = document.onTableCellChange(ref, before, next)
    fun formatTableCells(ref: TableCellRef, scope: com.notessync.app.notes.tables.TableFormatScope, kind: FormatKind, value: String?) =
        document.formatTableCells(ref, scope, kind, value)
    fun clearTableStyles(ref: TableCellRef, scope: com.notessync.app.notes.tables.TableFormatScope) = document.clearTableStyles(ref, scope)
    fun resizeTableColumn(id: String, column: Int, width: Int) = document.resizeTableColumn(id, column, width)
    fun resizeTableRow(id: String, row: Int, height: Int) = document.resizeTableRow(id, row, height)
    fun insertVoiceText(raw: String, structure: com.notessync.app.settings.VoiceStructure) {
        if (!isReady || isClosing || accessDenied || previewMode) return
        document.insertVoiceText(raw, structure)
    }
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
        if (isClosing || protection.busy) return
        if (!isTask) isTask = true
        else if (exists) protection.convertToNote() else applyTaskConversion(null)
    }
    private fun applyTaskConversion(note: NoteEntity?) {
        isTask = false; taskPriority = 0; taskDueAt = null; isCompleted = false; taskCompletedAt = null
        taskElapsedMillis = 0L; taskTimerStartedAt = null; taskCountdownMillis = 0L; taskCountdownEndAt = null
        isProtected = false; passwordHash = null; passwordSalt = null
        if (note != null) {
            val previous = lastSavedNote
            val converted = previous?.toOrdinaryNote(note.updatedAt)
            // A metadata command must not rebase an unchanged stale editor onto a newer peer body.
            lastSavedNote = if (converted == note) note else previous?.toOrdinaryNote(previous.updatedAt) ?: note
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
            reminderAt = null
            cancelCountdownAlarm()
            timing.clearReminder()
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
    private val protection = EditorTaskProtection(app, db, viewModelScope, saveMutex,
        object : EditorProtectionParent {
            override val editable get() = loaded && !accessDenied && !deleted
            override val task get() = isTask
            override val isProtected: Boolean get() = this@NoteEditorViewModel.isProtected
            override val noteId get() = currentNoteId
            override val syncId get() = this@NoteEditorViewModel.syncId
            override suspend fun flush() = saveDocument(force = true)
            override fun denied() { accessDenied = true }
            override fun changed(record: PasswordRecord?, beforeVersion: Long, timestamp: Long) {
                this@NoteEditorViewModel.isProtected = record != null; passwordHash = record?.hash; passwordSalt = record?.salt
                lastSavedNote = lastSavedNote?.let { saved -> saved.copy(isProtected = isProtected, passwordHash = passwordHash,
                    passwordSalt = passwordSalt, updatedAt = if (saved.updatedAt == beforeVersion) timestamp else saved.updatedAt, dirty = true) }
            }
            override fun converted(note: NoteEntity) = applyTaskConversion(note)
            override fun failed() { message = EditorMessage.SAVE_FAILED }
        })
    val taskProtectionBusy get() = protection.busy
    fun protectTask(password: String) { if (!isClosing) protection.protect(password) }
    fun removeTaskProtection(password: String, onResult: (Boolean) -> Unit) {
        if (isClosing) onResult(false) else protection.remove(password, onResult)
    }
    fun cancelTaskProtection() = protection.cancel()
    fun lockTask() {
        protection.cancel()
        document.clearHistory()
        ProtectionSession.lockTask(syncId)
    }

    fun clearMessage() {
        message = null
    }

    fun assignNotebook(notebook: NotebookEntity?) {
        if (isDiaryEntry || (notebook != null && !NotebookTarget(notebook.id, notebook.syncId)
                .allows(notebook, ProtectionSession.state.value)) || !loaded || accessDenied || isClosing) return
        notebookId = notebook?.id
        notebookName = notebook?.name
        notebookTarget = notebook?.let { NotebookTarget(it.id, it.syncId) }
        notebookChoiceDirty = true
        notebookChoiceVersion++
        viewModelScope.launch {
            try { saveNow() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message = EditorMessage.SAVE_FAILED
            }
        }
    }

    private val tags = EditorTags(db, viewModelScope, saveMutex, object : EditorTagsParent {
        override val editable get() = loaded && !isClosing && !accessDenied && !deleted
        override val noteId get() = currentNoteId
        override val syncId get() = this@NoteEditorViewModel.syncId
        override suspend fun flush() = saveNow(force = true)
        override fun changed(names: List<String>, beforeVersion: Long, version: Long) {
            noteTags = names; adoptMetadataVersion(beforeVersion, version)
        }
        override fun failed() { message = EditorMessage.SAVE_FAILED }
    })
    fun addTag(name: String) = tags.change(name, add = true)
    fun removeTag(name: String) = tags.change(name, add = false)

    // ---------- Вложения (Этап 3) ----------

    fun startAudioRecording() = media.startAudioRecording()
    fun attach(uri: Uri) = media.attach(uri)
    suspend fun prepareCameraCapture(): Uri? = media.prepareCameraCapture()
    fun finishCameraCapture(success: Boolean) = media.finishCameraCapture(success)
    suspend fun savePhotoSize(photo: AttachmentEntity, requested: PhotoDisplaySize): Boolean = media.savePhotoSize(photo, requested)
    fun removeAttachment(attachment: AttachmentEntity) = media.removeAttachment(attachment)

    private fun adoptMetadataVersion(beforeVersion: Long, version: Long) {
        val saved = lastSavedNote ?: return
        // Only this exact saved document may adopt its own relation revision; a peer edit stays stale.
        if (saved.updatedAt == beforeVersion && version != beforeVersion) {
            lastSavedNote = saved.copy(updatedAt = version, dirty = true)
        }
    }
    private suspend fun accessibleParent(): NoteEntity? {
        val current = noteDao.getById(currentNoteId) ?: return null
        return current.takeIf { it.syncId == syncId && !it.isDeleted && !it.isPurged && NoteActions(app, db).canAccess(it) }
    }
    // ---------- Напоминания (Этап 3) ----------

    // ---------- Удаление / выход ----------

    suspend fun discardIfEmptyOnExit() = saveMutex.withLock {
        if (loaded && createdHere && !deleted && !accessDenied && title.isBlank() && contentField.text.isBlank() && !hasTables && !hasSubtasks && reminderAt == null && taskDueAt == null && exists) {
            withContext(NonCancellable) {
                db.withTransaction {
                    val fresh = accessibleParent() ?: return@withTransaction
                    if (fresh.title.isBlank() && fresh.content.isBlank() && fresh.tablesData.isBlank() && fresh.reminderAt == null && fresh.taskDueAt == null &&
                        fresh.taskElapsedMillis <= 0 && fresh.taskTimerStartedAt == null && fresh.taskCountdownMillis <= 0 && fresh.taskCountdownEndAt == null &&
                        attachmentDao.countByNoteId(currentNoteId) == 0 && db.subtaskDao().get(currentNoteId).isEmpty()) {
                        noteDao.softDelete(currentNoteId); deleted = true
                    }
                }
            }
        }
        document.clearHistory()
    }

    suspend fun deleteNow(): Long = saveMutex.withLock {
        withContext(NonCancellable) {
            val current = if (exists) accessibleParent() else null
            val deletedId = if (current == null) -1L else {
                // The general list must never act on Journal rows; this explicitly authorized
                // editor supplies the fresh private parent, rechecked inside NoteActions' transaction.
                val diaryId = current.notebookId?.let { db.notebookDao().getById(it) }?.takeIf { it.isDiary }?.id
                val changed = NoteActions(app, db).apply(setOf(currentNoteId), NoteActions.Action.TRASH, mapOf(currentNoteId to syncId), diaryId)
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
