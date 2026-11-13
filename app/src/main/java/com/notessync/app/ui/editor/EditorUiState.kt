package com.notessync.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable

@Stable
internal class EditorUiState {
    var showPhotoMenu by mutableStateOf(false)
    var showAddTable by mutableStateOf(false)
    var openPhoto by mutableStateOf<Long?>(null)
    var openTable by mutableStateOf<String?>(null)
    var showDeleteDialog by mutableStateOf(false)
    var showNotebookMenu by mutableStateOf(false)
    var showTagDialog by mutableStateOf(false)
    var showColorDialog by mutableStateOf(false)
    var showTimerResetDialog by mutableStateOf(false)
    var showCountdownMenu by mutableStateOf(false)
    var showCountdownUntil by mutableStateOf(false)
    var showCountdownCustomDialog by mutableStateOf(false)
    var showTaskPasswordDialog by mutableStateOf(false)
    var showTaskProtectionMenu by mutableStateOf(false)
    var showRemoveTaskProtection by mutableStateOf(false)
    var taskProtectionError by mutableStateOf(false)
    var showReminderMenu by mutableStateOf(false)
    var showFormattingPanel by mutableStateOf(false)
    var showTransform by mutableStateOf(false)
    var showDatePicker by mutableStateOf(false)
    var showTimePickerFor by mutableStateOf<Long?>(null)
}
