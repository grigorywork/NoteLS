package com.notessync.app.ui.notes

import com.notessync.app.notes.text.*
import com.notessync.app.ui.notes.md.ParagraphEdit
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.attachments.PhotoAttachmentStore
import com.notessync.app.data.attachments.PhotoTooLargeException
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.ui.notes.md.MarkdownEdit
import com.notessync.app.ui.notes.md.MarkdownLite
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class EditorMessage {
    FORMATTING_LIMIT,
    PHOTO_TOO_LARGE,
    PHOTO_IMPORT_FAILED,
    REMINDER_IN_PAST,
    REMINDER_NOTIFICATIONS_DISABLED,
    REMINDER_SCHEDULE_FAILED
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteEditorViewModel(
    application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val app = application
    private val db = AppDatabase.get(application)
    private val noteDao = db.noteDao()
    private val tagDao = db.tagDao()
    private val attachmentDao = db.attachmentDao()
    private val photoStore = PhotoAttachmentStore(application)

    /** id заметки; -1 — новая, создаётся при первом сохранении. */
    var currentNoteId: Long = savedState.get<Long>("noteId") ?: -1L
        private set

    private var createdHere = false
    private var deleted = false
    private var loaded = false
    private var lastSavedNote: NoteEntity? = null

    var formatting by mutableStateOf(TextFormatting())
        private set
    private var pendingFormatting by mutableStateOf<Map<FormatKind, String?>>(emptyMap())
    var taskPriority by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var title by mutableStateOf("")
    var contentField by mutableStateOf(TextFieldValue(""))
    var isFavorite by mutableStateOf(false)
    var isTask by mutableStateOf(savedState.get<Boolean>("isTask") ?: false)
    var isCompleted by mutableStateOf(false)
    var taskColor by mutableStateOf(DEFAULT_TASK_COLOR)
    var taskElapsedMillis by mutableLongStateOf(0L)
    var taskTimerStartedAt by mutableStateOf<Long?>(null)
    var taskCountdownMillis by mutableLongStateOf(0L)
    var taskCountdownEndAt by mutableStateOf<Long?>(null)
    var taskCompletedAt by mutableStateOf<Long?>(null)
    var isProtected by mutableStateOf(false)
    var reminderAt by mutableStateOf<Long?>(null)
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
            if (currentNoteId != -1L) {
                noteDao.getById(currentNoteId)?.let { note ->
                    lastSavedNote = note
                    val notebook = note.notebookId?.let { db.notebookDao().getById(it) }
                    val notebookLocked = notebook?.let {
                        it.isProtected && !ProtectionSession.isUnlocked(it)
                    } == true
                    val noteLocked = note.isProtected && !ProtectionSession.isUnlocked(note)
                    if (notebookLocked || noteLocked) {
                        accessDenied = true
                        return@launch
                    }
                    title = note.title
                    contentField = TextFieldValue(note.content, TextRange(note.content.length))
                    formatting = withContext(Dispatchers.Default) { TextFormatting.decode(note.textFormat, note.content.length) }
                    taskPriority = note.taskPriority
                    isFavorite = note.isFavorite
                    isTask = note.isTask
                    isCompleted = note.isCompleted
                    taskColor = note.taskColor
                    taskElapsedMillis = note.taskElapsedMillis
                    taskTimerStartedAt = note.taskTimerStartedAt
                    taskCountdownMillis = note.taskCountdownMillis
                    taskCountdownEndAt = note.taskCountdownEndAt
                    taskCompletedAt = note.taskCompletedAt
                    isProtected = note.isProtected
                    passwordHash = note.passwordHash
                    passwordSalt = note.passwordSalt
                    reminderAt = note.reminderAt
                    notebookId = note.notebookId
                    dayKey = note.dayKey
                    notebookName = notebook?.name
                    createdAt = note.createdAt
                    syncId = note.syncId
                    noteTags = tagDao.getTagNamesForNote(note.id)
                }
            }
            loaded = true
            startAutosave()
        }
    }

    private fun startAutosave() {
        viewModelScope.launch {
            snapshotFlow {
                AutosaveKey(
                    title, contentField.text, formatting, taskPriority, isFavorite, reminderAt,
                    isTask, isCompleted, taskColor, taskElapsedMillis, taskTimerStartedAt,
                    taskCountdownMillis, taskCountdownEndAt, taskCompletedAt, isProtected
                )
            }
                .drop(1)
                .debounce(500)
                .collectLatest { saveNow() }
        }
    }

    private data class AutosaveKey(
        val title: String,
        val content: String,
        val formatting: TextFormatting,
        val taskPriority: Int,
        val favorite: Boolean,
        val reminderAt: Long?,
        val isTask: Boolean,
        val isCompleted: Boolean,
        val taskColor: String,
        val taskElapsedMillis: Long,
        val taskTimerStartedAt: Long?,
        val taskCountdownMillis: Long,
        val taskCountdownEndAt: Long?,
        val taskCompletedAt: Long?,
        val isProtected: Boolean
    )

    suspend fun saveNow(force: Boolean = false) {
        if (deleted) return
        if (!force && !exists && title.isBlank() && contentField.text.isBlank()) return

        val now = System.currentTimeMillis()
        val note = NoteEntity(
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
            dayKey = dayKey,
            isTask = isTask,
            isCompleted = isCompleted,
            taskColor = taskColor,
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
        // Opening/closing an unchanged note must not write the DB or produce another ZIP.
        val previous = lastSavedNote
        if (previous != null && note.copy(updatedAt = previous.updatedAt, dirty = previous.dirty) == previous) return
        if (exists) {
            noteDao.update(note)
        } else {
            val newId = noteDao.upsert(note)
            currentNoteId = newId
            createdHere = true
            _noteIdFlow.value = newId
        }
        createdAt = note.createdAt
        lastSavedNote = note.copy(id = currentNoteId)
        if (dayKey != null) CalendarWidgetProvider.refreshAll(app, force)
        BackupManager.noteChanged()
    }

    /**
     * Аудит 2026-11-11: сброс отложенного автосохранения при выходе. Раньше быстрый
     * ввод и мгновенный выход (в пределах 500 мс debounce) терял хвост правки,
     * потому что экран закрывался раньше срабатывания автосохранения.
     */
    suspend fun flushOnExit() {
        // Пустая новая заметка не создаётся — условие как у раннего выхода saveNow.
        if (exists || title.isNotBlank() || contentField.text.isNotBlank()) {
            saveNow(force = true)
        }
        discardIfEmptyOnExit()
        BackupManager.requestAutoBackup(app, afterNote = true)
    }

    // ---------- Форматирование (Этап 3) ----------

    /** Ввод в поле текста: перехватывает Enter для автопродолжения списков. */
    fun onContentChange(newValue: TextFieldValue) {
        updateContent(MarkdownEdit.handleEnter(contentField, newValue))
    }

    private fun updateContent(next: TextFieldValue) {
        val before = contentField
        if (before.text == next.text) {
            if (before.selection != next.selection) pendingFormatting = emptyMap()
        } else {
            formatting = try { formatting.edited(before.text, next.text, before.selection.start, before.selection.end, pendingFormatting) }
            catch (_: FormattingLimitReached) {
                pendingFormatting = emptyMap(); message = EditorMessage.FORMATTING_LIMIT
                formatting.edited(before.text, next.text, before.selection.start, before.selection.end)
            }
        }
        contentField = next
    }
    val activeFormatting: Map<FormatKind, String?>
        get() = formatting.at(contentField.selection.min, preferLeft = contentField.selection.collapsed) + pendingFormatting

    fun formatSelection(kind: FormatKind, value: String?) {
        if (value != null && !kind.valid(value)) return
        val selection = contentField.selection
        if (selection.collapsed) pendingFormatting = pendingFormatting + (kind to value)
        else try { formatting = formatting.apply(selection.min, selection.max, kind, value) }
        catch (_: FormattingLimitReached) { message = EditorMessage.FORMATTING_LIMIT }
    }
    fun selectAllContent() {
        pendingFormatting = emptyMap()
        contentField = contentField.copy(selection = TextRange(0, contentField.text.length))
    }
    fun toggleBold() = toggleTextStyle(FormatKind.BOLD)
    fun toggleItalic() = toggleTextStyle(FormatKind.ITALIC)
    fun toggleTextStyle(kind: FormatKind) {
        val legacy = when (kind) {
            FormatKind.BOLD -> MarkdownEdit.isInsideWrap(contentField.text, contentField.selection.min, "**")
            FormatKind.ITALIC -> MarkdownEdit.isInsideWrap(contentField.text, contentField.selection.min, "*")
            else -> false
        }
        val active = activeFormatting[kind]?.let { it == "1" } ?: legacy
        formatSelection(kind, if (active) "0" else "1")
    }
    private fun updateLines(next: TextFieldValue) {
        formatting = formatting.editedLines(contentField.text, next.text)
        pendingFormatting = emptyMap(); contentField = next
    }
    fun toggleHeading(level: Int = 1) { updateLines(MarkdownEdit.toggleLinePrefix(contentField, "#".repeat(level.coerceIn(1, 3)) + " ")) }
    fun toggleList(kind: MarkdownEdit.ListKind = MarkdownEdit.ListKind.BULLET) { updateLines(MarkdownEdit.toggleListKind(contentField, kind)) }
    fun toggleChecklist() { updateLines(MarkdownEdit.toggleListKind(contentField, MarkdownEdit.ListKind.CHECKLIST)) }
    fun formatParagraph(kind: FormatKind, value: String) {
        if (kind != FormatKind.ALIGN && kind != FormatKind.INDENT) return
        val range = ParagraphEdit.bounds(contentField)
        if (range.isEmpty()) formatSelection(kind, value)
        else try { formatting = formatting.apply(range.first, range.last + 1, kind, value) }
        catch (_: FormattingLimitReached) { message = EditorMessage.FORMATTING_LIMIT }
    }
    fun changeIndent(delta: Int) {
        val level = activeFormatting[FormatKind.INDENT]?.toIntOrNull() ?: 0
        formatParagraph(FormatKind.INDENT, (level + delta).coerceIn(0, 4).toString())
    }
    fun choosePriority(value: Int) {
        if (!isTask) return
        val next = com.notessync.app.ui.tasks.TaskPriority.from(value).value
        if (next == taskPriority) return
        taskPriority = next; viewModelScope.launch { saveNow(force = true) }
    }

    /** Тап по строке предпросмотра: переключить чек-бокс, если он там есть. */
    fun toggleCheckboxAtLine(lineIndex: Int) {
        MarkdownLite.toggleCheckbox(contentField.text, lineIndex)?.let {
            // Длина текста не меняется (префиксы равной длины) — курсор остаётся на месте.
            updateContent(TextFieldValue(it, contentField.selection))
        }
    }

    // ---------- Голосовой ввод (Этап 32) ----------

    /** Вставка распознанной речи в позицию курсора с выбранной структуризацией. */
    fun insertVoiceText(raw: String, structure: com.notessync.app.settings.VoiceStructure) {
        val processed = when (structure) {
            com.notessync.app.settings.VoiceStructure.RAW ->
                com.notessync.app.voice.TextStructurer.normalize(raw)
            com.notessync.app.settings.VoiceStructure.POINTS ->
                com.notessync.app.voice.TextStructurer.toPoints(raw)
            com.notessync.app.settings.VoiceStructure.MAIN_IDEA ->
                com.notessync.app.voice.TextStructurer.mainIdea(raw)
        }
        if (processed.isBlank()) return
        val current = contentField
        val text = current.text
        val start = current.selection.min.coerceIn(0, text.length)
        val end = current.selection.max.coerceIn(0, text.length)
        val before = text.substring(0, start)
        val after = text.substring(end)
        val isList = structure == com.notessync.app.settings.VoiceStructure.POINTS
        val prefix = when {
            before.isEmpty() || before.endsWith("\n") -> ""
            isList -> "\n"
            before.endsWith(" ") -> ""
            else -> " "
        }
        val suffix = when {
            after.isEmpty() -> ""
            isList && !after.startsWith("\n") -> "\n"
            else -> ""
        }
        val inserted = prefix + processed + suffix
        updateContent(TextFieldValue(
            before + inserted + after,
            TextRange((before + inserted).length)
        ))
    }

    // ---------- Организация (Этап 2) ----------

    fun toggleFavorite() { isFavorite = !isFavorite }

    fun toggleTask() {
        isTask = !isTask
        if (!isTask) {
            taskPriority = 0
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
        taskColor = color
    }

    fun toggleTaskTimer() {
        if (!isTask || (isCompleted && taskTimerStartedAt == null)) return
        val timer = TaskTimer.toggle(
            taskElapsedMillis,
            taskTimerStartedAt,
            System.currentTimeMillis()
        )
        taskElapsedMillis = timer.elapsedMillis
        taskTimerStartedAt = timer.startedAt
        viewModelScope.launch { saveNow(force = true) }
    }

    fun resetTaskTimer() {
        if (taskElapsedMillis == 0L && taskTimerStartedAt == null) return
        taskElapsedMillis = 0L
        taskTimerStartedAt = null
        viewModelScope.launch { saveNow(force = true) }
    }

    // ---------- Обратный отсчёт (Этап 30) ----------

    /** Остаток в миллисекундах на момент [now]. */
    fun countdownRemainingAt(now: Long = System.currentTimeMillis()): Long =
        TaskTimer.countdownRemaining(taskCountdownMillis, taskCountdownEndAt, now)

    fun countdownRunning(): Boolean = taskCountdownEndAt != null

    fun countdownFinishedNow(now: Long = System.currentTimeMillis()): Boolean =
        TaskTimer.countdownFinished(taskCountdownMillis, taskCountdownEndAt, now)

    /** Установить длительность и сразу запустить отсчёт. */
    fun startCountdown(durationMillis: Long) {
        if (!isTask || durationMillis <= 0L) return
        val now = System.currentTimeMillis()
        taskCountdownMillis = durationMillis
        taskCountdownEndAt = TaskTimer.countdownStart(durationMillis, now)
        viewModelScope.launch {
            saveNow(force = true)
            scheduleCountdownAlarm()
        }
    }

    /** Пауза: остаток фиксируется, будильник снимается. */
    fun pauseCountdown() {
        val endAt = taskCountdownEndAt ?: return
        val now = System.currentTimeMillis()
        taskCountdownMillis = (endAt - now).coerceAtLeast(0L)
        taskCountdownEndAt = null
        cancelCountdownAlarm()
        viewModelScope.launch { saveNow(force = true) }
    }

    /** Продолжить с оставшегося времени. */
    fun resumeCountdown() {
        if (!isTask || taskCountdownEndAt != null || taskCountdownMillis <= 0L) return
        taskCountdownEndAt = TaskTimer.countdownStart(taskCountdownMillis, System.currentTimeMillis())
        viewModelScope.launch {
            saveNow(force = true)
            scheduleCountdownAlarm()
        }
    }

    /** Снять таймер полностью. */
    fun cancelCountdown() {
        if (taskCountdownMillis == 0L && taskCountdownEndAt == null) return
        taskCountdownMillis = 0L
        taskCountdownEndAt = null
        cancelCountdownAlarm()
        viewModelScope.launch { saveNow(force = true) }
    }

    private suspend fun scheduleCountdownAlarm() {
        val endAt = taskCountdownEndAt ?: return
        if (!exists) return
        val notebookProtected = notebookId?.let {
            db.notebookDao().getById(it)?.isProtected
        } == true
        val notificationTitle = if (isProtected || notebookProtected) {
            "Защищённая запись"
        } else {
            title.ifBlank { "Без названия" }
        }
        val scheduled = ReminderScheduler.scheduleTimer(app, currentNoteId, notificationTitle, endAt)
        if (!scheduled) {
            // Без будильника таймер продолжает жить только в интерфейсе.
            message = EditorMessage.REMINDER_SCHEDULE_FAILED
        }
    }

    private fun cancelCountdownAlarm() {
        if (exists) ReminderScheduler.cancelTimer(app, currentNoteId)
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

    fun attach(uri: Uri) {
        viewModelScope.launch {
            try {
                saveNow(force = true)
                val attachmentSyncId = UUID.randomUUID().toString()
                val stored = withContext(Dispatchers.IO) {
                    photoStore.import(uri, attachmentSyncId)
                }
                attachmentDao.upsert(
                    AttachmentEntity(
                        syncId = attachmentSyncId,
                        noteId = currentNoteId,
                        filePath = stored.file.absolutePath,
                        mimeType = stored.mimeType,
                        sizeBytes = stored.sizeBytes
                    )
                )
                BackupManager.noteChanged()
                noteDao.getById(currentNoteId)?.let { note ->
                    noteDao.update(note.copy(dirty = true, updatedAt = System.currentTimeMillis()))
                }
            } catch (_: PhotoTooLargeException) {
                message = EditorMessage.PHOTO_TOO_LARGE
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = EditorMessage.PHOTO_IMPORT_FAILED
            }
        }
    }

    fun removeAttachment(attachment: AttachmentEntity) {
        viewModelScope.launch {
            attachmentDao.deleteById(attachment.id)
            BackupManager.noteChanged()
            File(attachment.filePath).delete()
            noteDao.getById(attachment.noteId)?.let { note ->
                noteDao.update(note.copy(dirty = true, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    // ---------- Напоминания (Этап 3) ----------

    fun setReminder(at: Long) {
        if (at <= System.currentTimeMillis()) {
            message = EditorMessage.REMINDER_IN_PAST
            return
        }
        if (!ReminderScheduler.canNotify(app)) {
            message = EditorMessage.REMINDER_NOTIFICATIONS_DISABLED
            return
        }
        reminderAt = at
        viewModelScope.launch {
            saveNow(force = true)
            val notebookProtected = notebookId?.let {
                db.notebookDao().getById(it)?.isProtected
            } == true
            val notificationTitle = if (isProtected || notebookProtected) {
                "Защищённая запись"
            } else {
                title.ifBlank { "Без названия" }
            }
            val scheduled = ReminderScheduler.schedule(app, currentNoteId, notificationTitle, at)
            if (!scheduled) {
                reminderAt = null
                saveNow(force = true)
                message = EditorMessage.REMINDER_SCHEDULE_FAILED
            }
        }
    }

    fun reportReminderNotificationsDisabled() {
        message = EditorMessage.REMINDER_NOTIFICATIONS_DISABLED
    }

    fun clearReminder() {
        reminderAt = null
        if (exists) ReminderScheduler.cancel(app, currentNoteId)
        viewModelScope.launch { saveNow(force = true) }
    }

    // ---------- Удаление / выход ----------

    suspend fun discardIfEmptyOnExit() {
        val hasAttachments = exists && attachmentDao.countByNoteId(currentNoteId) > 0
        if (loaded && createdHere && title.isBlank() && contentField.text.isBlank() &&
            reminderAt == null && !hasAttachments && exists
        ) {
            deleted = true
            noteDao.softDelete(currentNoteId)
        }
    }

    suspend fun deleteNow(): Long {
        deleted = true
        if (exists) {
            ReminderScheduler.cancel(app, currentNoteId)
            ReminderScheduler.cancelTimer(app, currentNoteId)
            noteDao.softDelete(currentNoteId)
            if (dayKey != null) CalendarWidgetProvider.refreshAll(app)
        }
        return currentNoteId
    }
}
