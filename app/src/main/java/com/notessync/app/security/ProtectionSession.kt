package com.notessync.app.security

import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProtectionState(
    val notebookIds: Set<Long> = emptySet(),
    val taskSyncIds: Set<String> = emptySet()
)

object ProtectionSession {
    private val _state = MutableStateFlow(ProtectionState())
    val state: StateFlow<ProtectionState> = _state.asStateFlow()

    fun isUnlocked(notebook: NotebookEntity): Boolean =
        !notebook.isProtected || notebook.id in _state.value.notebookIds

    fun isUnlocked(note: NoteEntity): Boolean =
        !note.isProtected || note.syncId in _state.value.taskSyncIds

    fun unlockNotebook(notebookId: Long) {
        _state.value = _state.value.copy(
            notebookIds = _state.value.notebookIds + notebookId
        )
    }

    fun lockNotebook(notebookId: Long) {
        _state.value = _state.value.copy(
            notebookIds = _state.value.notebookIds - notebookId
        )
    }

    fun unlockTask(syncId: String) {
        _state.value = _state.value.copy(
            taskSyncIds = _state.value.taskSyncIds + syncId
        )
    }

    fun lockTask(syncId: String) {
        _state.value = _state.value.copy(
            taskSyncIds = _state.value.taskSyncIds - syncId
        )
    }
}
