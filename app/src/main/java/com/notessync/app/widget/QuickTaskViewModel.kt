package com.notessync.app.widget

import com.notessync.app.i18n.uiText

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.backup.BackupManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

@OptIn(FlowPreview::class)
class QuickTaskViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val store = QuickTaskStore(app)
    private val gate = Mutex()
    var text by mutableStateOf(saved.get<String>("quickText").orEmpty())
        private set
    var dayKey by mutableLongStateOf(safeDay(saved.get<Long>("quickDay") ?: saved.get<Long>("dayKey")))
        private set
    var reminderAt by mutableStateOf(saved.get<Long>("quickReminder"))
        private set
    private var identity: String = saved["quickIdentity"] ?: UUID.randomUUID().toString()
    var currentId by mutableLongStateOf(saved.get<Long>("quickId") ?: 0L)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isSaved by mutableStateOf(text.isBlank())
        private set

    init {
        saved["quickIdentity"] = identity; saved["quickDay"] = dayKey
        viewModelScope.launch {
            snapshotFlow { draft() }.debounce(500).collect {
                if (it.text.isNotBlank()) persist()
            }
        }
    }

    private fun draft() = QuickTaskDraft(identity, text, dayKey, reminderAt)
    fun changeText(value: String) {
        if (busy) return
        if (value.length > MAX_TEXT) { error = uiText("Быстрая задача: не более 32 000 символов."); return }
        text = value; saved["quickText"] = value; isSaved = false; error = null
    }
    fun setReminder(at: Long?): Boolean {
        if (busy) return false
        if (at != null && at <= System.currentTimeMillis()) { error = uiText("Выберите время в будущем."); return false }
        reminderAt = at; saved["quickReminder"] = at; isSaved = false; error = null
        return true
    }

    fun changeDay(day: Long) {
        if (busy) return
        val date = runCatching { LocalDate.ofEpochDay(day) }.getOrNull() ?: return
        val zone = java.time.ZoneId.systemDefault()
        val nextReminder = reminderAt?.let { previous ->
            runCatching { date.atTime(java.time.Instant.ofEpochMilli(previous).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        }
        dayKey = day; saved["quickDay"] = day
        reminderAt = nextReminder
        saved["quickReminder"] = reminderAt; isSaved = false
        error = if (nextReminder != null && nextReminder <= System.currentTimeMillis()) uiText("Выберите будущее время или уберите напоминание.") else null
    }

    fun notificationDenied() { error = uiText("Без разрешения на уведомления напоминание не добавлено.") }

    suspend fun persist(): Boolean = withContext(NonCancellable) {
        gate.withLock {
            val snapshot = draft()
            try {
                val result = store.save(snapshot)
                currentId = result.id; saved["quickId"] = result.id
                if (result.removed) {
                    identity = UUID.randomUUID().toString(); saved["quickIdentity"] = identity
                }
                error = when (result.warning) {
                    QuickTaskWarning.PAST -> uiText("Время прошло. Выберите новое или уберите напоминание.")
                    QuickTaskWarning.NOTIFICATIONS -> uiText("Уведомления выключены. Разрешите их или уберите напоминание.")
                    QuickTaskWarning.SCHEDULE -> uiText("Не удалось поставить напоминание. Повторите попытку.")
                    null -> null
                }
                isSaved = result.warning == null && snapshot.text == text && snapshot.reminderAt == reminderAt && snapshot.dayKey == dayKey
                result.warning == null
            } catch (_: Exception) {
                error = uiText("Не удалось сохранить. Текст оставлен здесь; повторите попытку.")
                isSaved = false
                false
            }
        }
    }

    fun next(onReady: () -> Unit = {}) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        try {
            if (persist()) {
                text = ""; reminderAt = null; currentId = 0
                identity = UUID.randomUUID().toString()
                saved["quickText"] = ""; saved["quickReminder"] = null; saved["quickId"] = 0L
                saved["quickIdentity"] = identity
                isSaved = true; onReady()
            }
        } finally { busy = false }
    }

    fun close(onClosed: () -> Unit) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        try { if (persist()) { BackupManager.requestAutoBackup(getApplication(), afterNote = true); onClosed() } }
        finally { busy = false }
    }

    fun onStopped() = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (persist()) BackupManager.requestAutoBackup(getApplication(), afterNote = true)
    }

    companion object {
        const val MAX_TEXT = 32_000
        private fun safeDay(value: Long?): Long = value?.takeIf {
            runCatching { LocalDate.ofEpochDay(it).year in 1900..2100 }.getOrDefault(false)
        } ?: LocalDate.now().toEpochDay()
    }
}
