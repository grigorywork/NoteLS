package com.notessync.app.security

import com.notessync.app.data.local.model.NoteListEntry
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProtectionState(
    val notebookIds: Set<Long> = emptySet(),
    val taskSyncIds: Set<String> = emptySet()
)

object ProtectionSession {
    private val _state = MutableStateFlow(ProtectionState())
    val state: StateFlow<ProtectionState> = _state.asStateFlow()

    fun isUnlocked(notebook: NotebookEntity): Boolean =
        !notebook.isProtected || notebook.id in _state.value.notebookIds

    fun isUnlocked(note: NoteListEntry): Boolean =
        !note.isProtected || (note.syncId.isNotBlank() && note.syncId in _state.value.taskSyncIds)

    fun lockAll() { _state.value = ProtectionState() }

    fun unlockNotebook(notebookId: Long) {
        _state.update { it.copy(notebookIds = it.notebookIds + notebookId) }
    }

    fun lockNotebook(notebookId: Long) {
        _state.update { it.copy(notebookIds = it.notebookIds - notebookId) }
    }

    fun unlockTask(syncId: String) {
        if (syncId.isBlank()) return
        _state.update { it.copy(taskSyncIds = it.taskSyncIds + syncId) }
    }

    fun lockTask(syncId: String) {
        _state.update { it.copy(taskSyncIds = it.taskSyncIds - syncId) }
    }
}
