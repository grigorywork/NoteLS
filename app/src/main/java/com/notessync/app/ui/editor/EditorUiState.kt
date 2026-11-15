package com.notessync.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import com.notessync.app.ui.notes.text.FormatPopup

internal enum class EditorPopup { INSERT, NOTEBOOK, REMINDER, COUNTDOWN, TASK, MORE, TABLES, FORMAT }

@Stable
internal class EditorUiState {
    val selectionToolbar = EditorSelectionToolbar()
    private var currentPopup by mutableStateOf<EditorPopup?>(null)
    var popup: EditorPopup?
        get() = currentPopup
        set(value) {
            if (value != EditorPopup.FORMAT) { selectionToolbar.hide(); selectionRect = null }
            currentPopup = value
        }
    private var formatting by mutableStateOf<FormatPopup?>(null)
    var formatPopup: FormatPopup?
        get() = formatting.takeIf { popup == EditorPopup.FORMAT }
        set(value) {
            if (value != null && value.anchor != com.notessync.app.ui.notes.text.FormatAnchor.CONTEXT) selectionToolbar.hide()
            formatting = value
            if (value != null) popup = EditorPopup.FORMAT else if (popup == EditorPopup.FORMAT) popup = null
        }
    var showPhotoMenu: Boolean
        get() = popup == EditorPopup.INSERT
        set(value) { toggle(EditorPopup.INSERT, value) }
    var showAddTable by mutableStateOf(false)
    var openPhoto by mutableStateOf<Long?>(null)
    var openTable by mutableStateOf<String?>(null)
    var showDeleteDialog by mutableStateOf(false)
    var showNotebookMenu: Boolean
        get() = popup == EditorPopup.NOTEBOOK
        set(value) { toggle(EditorPopup.NOTEBOOK, value) }
    var showTagDialog by mutableStateOf(false)
    var showColorDialog by mutableStateOf(false)
    var showTimerResetDialog by mutableStateOf(false)
    var showCountdownMenu: Boolean
        get() = popup == EditorPopup.COUNTDOWN
        set(value) { toggle(EditorPopup.COUNTDOWN, value) }
    var showCountdownUntil by mutableStateOf(false)
    var showCountdownCustomDialog by mutableStateOf(false)
    var showTaskPasswordDialog by mutableStateOf(false)
    var showTaskProtectionMenu by mutableStateOf(false)
    var showRemoveTaskProtection by mutableStateOf(false)
    var taskProtectionError by mutableStateOf(false)
    var showReminderMenu: Boolean
        get() = popup == EditorPopup.REMINDER
        set(value) { toggle(EditorPopup.REMINDER, value) }
    var showTransform by mutableStateOf(false)
    var showDatePicker by mutableStateOf(false)
    var showTimePickerFor by mutableStateOf<Long?>(null)
    var showPriorityMenu by mutableStateOf(false)
    var showDeadlinePicker by mutableStateOf(false)
    var showMedia by mutableStateOf(false)
    var selectionRect by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    val modalOpen: Boolean get() = showAddTable || openTable != null || openPhoto != null || showDeleteDialog || showTagDialog ||
        showColorDialog || showTimerResetDialog || showCountdownUntil || showCountdownCustomDialog || showTransform ||
        showDatePicker || showTimePickerFor != null || showDeadlinePicker || showMedia
    fun dismissMenus() { popup = null; formatting = null }
    private fun toggle(menu: EditorPopup, show: Boolean) { if (show) popup = menu else if (popup == menu) popup = null }
}
