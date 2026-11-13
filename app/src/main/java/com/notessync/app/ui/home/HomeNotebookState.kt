package com.notessync.app.ui.home

import com.notessync.app.security.biometrics.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.ui.tasks.*
import androidx.compose.runtime.Stable

@Stable
internal class HomeNotebookState {
    var showCreateNotebook by mutableStateOf(false)
    var notebookToEdit by mutableStateOf<NotebookEntity?>(null)
    var notebookToDelete by mutableStateOf<NotebookEntity?>(null)
    var notebookToProtect by mutableStateOf<NotebookEntity?>(null)
    var notebookToUnlock by mutableStateOf<NotebookEntity?>(null)
    var notebookToEnrollBiometric by mutableStateOf<NotebookEntity?>(null)
    var biometricPasswordError by mutableStateOf(false)
    var notebookToUnprotect by mutableStateOf<NotebookEntity?>(null)
    var taskToUnlock by mutableStateOf<NoteEntity?>(null)
    var notebookUnlockError by mutableStateOf(false)
    var notebookRemoveError by mutableStateOf(false)
    var taskUnlockError by mutableStateOf(false)
}
