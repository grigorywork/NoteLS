package com.notessync.app.ui.editor

import com.notessync.app.i18n.uiText
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.platform.testTag
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
    onRecordAudio: () -> Unit, onGallery: () -> Unit, onCamera: () -> Unit, onReminder: () -> Unit,
    voiceIdle: Boolean = true, showFormatting: Boolean = true, panelOnTop: Boolean = false) {
    Column(androidx.compose.ui.Modifier.navigationBarsPadding()) {
        if (!panelOnTop) EditorFormattingPanel(viewModel, ui, settingsState, notebooks, "formatting-panel-bottom",
            showFormatting, recordingBusy, voiceClosing, onRecordAudio, onGallery, onCamera, onReminder, voiceIdle)
        if (viewModel.isReady && !viewModel.accessDenied) {
            val dates = viewModel.editorDates
            EditorDatesFooter(dates?.createdAt, dates?.updatedAt)
        }
    }
}

/** Этап 79: панель оформления одна — она стоит либо снизу, либо сверху под заголовком. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditorFormattingPanel(viewModel: NoteEditorViewModel, ui: EditorUiState, settingsState: SettingsState,
    notebooks: List<NotebookEntity>, testTag: String, showToolbar: Boolean = true,
    recordingBusy: Boolean = false, voiceClosing: Boolean = false,
    onRecordAudio: () -> Unit = {}, onGallery: () -> Unit = {}, onCamera: () -> Unit = {},
    onReminder: () -> Unit = {}, voiceIdle: Boolean = true) {
    Box(androidx.compose.ui.Modifier.testTag(testTag)) {
        if (showToolbar) com.notessync.app.ui.notes.text.FormattingToolbar(
            active = viewModel.activeFormatting, advanced = settingsState.advancedFormatting,
            activeList = activeEditorList(viewModel), popup = ui.formatPopup, onPopup = { ui.formatPopup = it },
            actions = editorFormattingActions(viewModel), enabled = viewModel.isReady && !viewModel.isClosing && !viewModel.previewMode,
            showTables = settingsState.showTables,
            onTable = { ui.dismissMenus(); if (viewModel.tables?.blocks.isNullOrEmpty()) ui.showAddTable = true else ui.popup = EditorPopup.TABLES },
            onInsert = { ui.showPhotoMenu = true })
        DropdownMenu(ui.popup == EditorPopup.TABLES, { ui.dismissMenus() }) {
            DropdownMenuItem(text = { Text(uiText("Новая таблица")) }, onClick = { ui.dismissMenus(); ui.showAddTable = true })
            viewModel.tables?.blocks.orEmpty().forEachIndexed { index, table ->
                DropdownMenuItem(text = { Text(uiText("Таблица %1\$s × %2\$s", table.rowCount, table.columnCount) + " · ${index + 1}") },
                    onClick = { ui.dismissMenus(); ui.openTable = table.id })
            }
        }
        DropdownMenu(expanded = ui.showPhotoMenu, onDismissRequest = { ui.showPhotoMenu = false }) {
            if (!viewModel.isDiaryEntry) DropdownMenuItem(text = { Text(uiText("Блокнот")) }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                onClick = { ui.showNotebookMenu = true })
            DropdownMenuItem(text = { Text(uiText("Теги")) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                onClick = { ui.dismissMenus(); ui.showTagDialog = true })
            DropdownMenuItem(text = { Text(uiText("Сигнал")) }, leadingIcon = { Icon(Icons.Default.Alarm, null) },
                onClick = { ui.dismissMenus(); onReminder() })
            if (settingsState.aiEnabled) DropdownMenuItem(text = { Text(uiText("Преобразовать текст")) },
                enabled = viewModel.isReady && !viewModel.isClosing && !viewModel.accessDenied && !viewModel.isPhotoBusy &&
                    viewModel.contentField.text.isNotBlank() && !recordingBusy && !voiceClosing && voiceIdle,
                onClick = { ui.dismissMenus(); ui.showTransform = true })
            if (viewModel.reminderAt != null) DropdownMenuItem(text = { Text(uiText("Убрать напоминание")) },
                onClick = { ui.dismissMenus(); viewModel.clearReminder() })
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
            expanded = ui.showNotebookMenu && !viewModel.isDiaryEntry,
            onDismissRequest = { ui.showNotebookMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(uiText("Без блокнота") + if (viewModel.notebookName == null) " ✓" else "") },
                onClick = {
                    viewModel.assignNotebook(null)
                    ui.showNotebookMenu = false
                }
            )
            notebooks.filterNot { it.isDiary }.forEach { notebook ->
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

internal fun activeEditorList(viewModel: NoteEditorViewModel): com.notessync.app.ui.notes.md.MarkdownEdit.ListKind? {
    val value = viewModel.contentField
    val cursor = value.selection.min.coerceIn(0, value.text.length)
    val start = value.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(-1)) + 1
    return com.notessync.app.ui.notes.md.ListMarkers.parse(value.text.substring(start))?.kind
}

internal fun editorFormattingActions(viewModel: NoteEditorViewModel) = com.notessync.app.ui.notes.text.FormattingActions(
    viewModel::toggleTextStyle, viewModel::formatSelection, viewModel::toggleHeading,
    viewModel::toggleLocalizedList, { viewModel.formatParagraph(com.notessync.app.notes.text.FormatKind.ALIGN, it) },
    viewModel::changeIndent, viewModel::selectAllContent)
