package com.notessync.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsState
import com.notessync.app.settings.FormattingBarPosition
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.text.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTopBar(viewModel: NoteEditorViewModel, ui: EditorUiState, settings: SettingsState,
    onExit: () -> Unit, onCalendar: () -> Unit, voiceEnabled: Boolean, onVoice: () -> Unit,
    onFormattingPanelChange: (FormattingBarPosition) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
        TopAppBar(title = { Text(uiText(if (viewModel.isTask) "Задача" else "Заметка"),
            style = MaterialTheme.typography.titleMedium, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onExit, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад"), Modifier.size(20.dp))
            } }, actions = {
                if (viewModel.historyEnabled) {
                    IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo && !viewModel.isClosing, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, uiText("Отменить"), Modifier.size(20.dp))
                    }
                    IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo && !viewModel.isClosing, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, uiText("Повторить", context = "history"), Modifier.size(20.dp))
                    }
                }
                Box {
                    IconButton(onClick = { ui.formatPopup = FormatPopup(FormatAnchor.TOP, FormatMenu.FULL) },
                        enabled = viewModel.isReady && !viewModel.isClosing && !viewModel.previewMode, modifier = Modifier.size(44.dp).semantics { contentDescription = uiText("Полное оформление") }) {
                        Text("Aa", style = MaterialTheme.typography.bodyLarge)
                    }
                    FormattingPopup(ui.formatPopup, FormatAnchor.TOP, { ui.formatPopup = it }, viewModel.activeFormatting,
                        settings.advancedFormatting, activeEditorList(viewModel), editorFormattingActions(viewModel))
                }
                Box {
                    IconButton(onClick = { ui.popup = EditorPopup.MORE }, enabled = !viewModel.isClosing, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.MoreVert, uiText("Действия"), Modifier.size(20.dp))
                    }
                    DropdownMenu(ui.popup == EditorPopup.MORE, { ui.dismissMenus() }) {
                        DropdownMenuItem(text = { Text(uiText("Голосовой ввод")) },
                            leadingIcon = { Icon(Icons.Default.Mic, null) }, enabled = voiceEnabled,
                            onClick = { ui.dismissMenus(); onVoice() })
                        DropdownMenuItem(text = { Text(uiText("Избранное")) },
                            leadingIcon = { Icon(if (viewModel.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, null) },
                            onClick = { viewModel.toggleFavorite(); ui.dismissMenus() })
                        DropdownMenuItem(text = { Text(uiText("Предпросмотр")) }, leadingIcon = {
                            Icon(if (viewModel.previewMode) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }, onClick = { viewModel.previewMode = !viewModel.previewMode; ui.dismissMenus() })
                        // Этап 79: одна панель оформления — переключаем её место прямо здесь.
                        val panelOnTop = settings.formattingBarPosition == FormattingBarPosition.TOP
                        DropdownMenuItem(text = { Text(uiText(if (panelOnTop) "Панель снизу" else "Панель сверху")) },
                            leadingIcon = { Icon(if (panelOnTop) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null) },
                            onClick = {
                                ui.dismissMenus()
                                onFormattingPanelChange(if (panelOnTop) FormattingBarPosition.BOTTOM else FormattingBarPosition.TOP)
                            })
                        DropdownMenuItem(text = { Text(uiText(if (viewModel.isTask) "Сделать заметкой" else "Сделать задачей")) },
                            enabled = !viewModel.taskProtectionBusy && !viewModel.isClosing,
                            leadingIcon = { Icon(Icons.Default.CheckBox, null) }, onClick = { ui.dismissMenus(); viewModel.toggleTask() })
                        DropdownMenuItem(text = { Text(uiText(if (viewModel.isTask) "Цвет задачи" else "Цвет заметки")) },
                            leadingIcon = { Icon(Icons.Default.Palette, null) }, onClick = { ui.dismissMenus(); ui.showColorDialog = true })
                        DropdownMenuItem(text = { Text(uiText("Календарь")) }, leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
                            onClick = { ui.dismissMenus(); onCalendar() })
                        DropdownMenuItem(text = { Text(uiText("Вложения")) }, leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            onClick = { ui.dismissMenus(); ui.showMedia = true })
                        if (viewModel.exists) DropdownMenuItem(text = { Text(uiText("Удалить")) },
                            enabled = !viewModel.isPhotoBusy, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { ui.dismissMenus(); ui.showDeleteDialog = true })
                    }
                }
            })
    }
}
