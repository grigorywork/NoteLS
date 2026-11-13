package com.notessync.app.ui.editor

import com.notessync.app.i18n.uiText
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.notessync.app.ui.notes.text.EditorHistoryActions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.reminders.ReminderTime
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.settings.SettingsState
import com.notessync.app.data.local.entity.NotebookEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditorBottomBar(viewModel: NoteEditorViewModel, ui: EditorUiState, settingsState: SettingsState,
    notebooks: List<NotebookEntity>, recordingBusy: Boolean, voiceClosing: Boolean,
    onRecordAudio: () -> Unit, onGallery: () -> Unit, onCamera: () -> Unit, onReminder: () -> Unit) {
    Box {
        Column {
            if (viewModel.historyEnabled) EditorHistoryActions(
                canUndo = viewModel.canUndo && !viewModel.isClosing,
                canRedo = viewModel.canRedo && !viewModel.isClosing,
                onUndo = viewModel::undo, onRedo = viewModel::redo)
            NavigationBar {
                NavigationBarItem(
                    selected = ui.showFormattingPanel,
                    onClick = { ui.showFormattingPanel = !ui.showFormattingPanel },
                    icon = { Icon(Icons.Default.FormatBold, contentDescription = null) },
                    label = { Text(uiText("Текст")) }
                )
                NavigationBarItem(
                    selected = false,
                    enabled = !viewModel.isPhotoBusy && !viewModel.isClosing && viewModel.isReady && !recordingBusy && !voiceClosing,
                    onClick = { ui.showPhotoMenu = true },
                    icon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                    label = { Text(uiText("Файлы")) }
                )
                NavigationBarItem(
                    selected = viewModel.reminderAt != null,
                    onClick = { onReminder() },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    label = { Text(uiText("Сигнал")) }
                )
                NavigationBarItem(
                    selected = viewModel.notebookName != null,
                    onClick = { ui.showNotebookMenu = true },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text(uiText("Блокнот")) }
                )
                NavigationBarItem(
                    selected = viewModel.noteTags.isNotEmpty(),
                    onClick = { ui.showTagDialog = true },
                    icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    label = { Text(uiText("Теги")) }
                )
            }
        }
        DropdownMenu(expanded = ui.showPhotoMenu, onDismissRequest = { ui.showPhotoMenu = false }) {
            if (settingsState.audioRecording) DropdownMenuItem(text = { Text(uiText("Записать аудио")) }, leadingIcon = { Icon(Icons.Default.Mic, null) }, onClick = {
                ui.showPhotoMenu = false
                onRecordAudio()
            })
            DropdownMenuItem(text = { Text(uiText("Из галереи")) }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) }, onClick = {
                ui.showPhotoMenu = false
                onGallery()
            })
            if (settingsState.cameraCapture) DropdownMenuItem(text = { Text(uiText("Снять фото")) }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) }, onClick = {
                ui.showPhotoMenu = false
                onCamera()
            })
        }
        DropdownMenu(
            expanded = ui.showReminderMenu,
            onDismissRequest = { ui.showReminderMenu = false }
        ) {
            // Этап 26: шаблоны сокращены вдвое — только ходовые варианты.
            listOf(
                uiText("Через 1 час") to 1,
                uiText("Через 2 часа") to 2,
                uiText("Через 6 часов") to 6
            ).forEach { (label, hours) ->
                DropdownMenuItem(
                    text = { Text(uiText(label)) },
                    onClick = {
                        ui.showReminderMenu = false
                        viewModel.setReminder(ReminderTime.afterHours(hours))
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(uiText("Завтра, 09:00")) },
                onClick = {
                    ui.showReminderMenu = false
                    viewModel.setReminder(ReminderTime.tomorrowMorning())
                }
            )
            DropdownMenuItem(
                text = { Text(uiText("Выбрать дату и время")) },
                onClick = {
                    ui.showReminderMenu = false
                    ui.showDatePicker = true
                }
            )
        }
        DropdownMenu(
            expanded = ui.showNotebookMenu,
            onDismissRequest = { ui.showNotebookMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(uiText("Без блокнота") + if (viewModel.notebookName == null) " ✓" else "") },
                onClick = {
                    viewModel.assignNotebook(null)
                    ui.showNotebookMenu = false
                }
            )
            notebooks.forEach { notebook ->
                DropdownMenuItem(
                    text = {
                        Text(
                            notebook.name + if (viewModel.notebookName == notebook.name) " ✓" else ""
                        )
                    },
                    onClick = {
                        viewModel.assignNotebook(notebook)
                        ui.showNotebookMenu = false
                    }
                )
            }
        }
    }
}
