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
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsState
import com.notessync.app.settings.FormattingBarPosition
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.text.*
import com.notessync.app.ai.QuickTransformAvailability
import com.notessync.app.ai.quickTransformAvailability
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTopBar(viewModel: NoteEditorViewModel, ui: EditorUiState, settings: SettingsState,
    onExit: () -> Unit, onCalendar: () -> Unit, voiceEnabled: Boolean, onVoice: () -> Unit,
    onFormattingPanelChange: (FormattingBarPosition) -> Unit, transformIdle: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val quickTransform = remember(context) { quickTransformAvailability(context) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
        TopAppBar(title = { Text(uiText(if (viewModel.isTask) "Задача" else "Заметка"),
            style = MaterialTheme.typography.titleMedium, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onExit, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад"), Modifier.size(20.dp))
            } }, actions = {
                if (settings.aiEnabled && settings.quickTransformButton && quickTransform == QuickTransformAvailability.AVAILABLE) {
                    IconButton(onClick = { ui.dismissMenus(); ui.showVoice = false; ui.showTransform = true },
                        enabled = viewModel.isReady && !viewModel.isClosing && !viewModel.accessDenied &&
                            !viewModel.previewMode && !viewModel.isPhotoBusy && !ui.modalOpen && transformIdle &&
                            viewModel.contentField.text.isNotBlank(),
                        modifier = Modifier.size(44.dp).testTag("quick-transform")) {
                        Icon(Icons.Default.AutoFixHigh, uiText("Преобразовать текст"), Modifier.size(20.dp))
                    }
                }
                if (viewModel.historyEnabled) {
                    IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo && !viewModel.isClosing, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, uiText("Отменить"), Modifier.size(20.dp))
                    }
                    IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo && !viewModel.isClosing, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, uiText("Повторить", context = "history"), Modifier.size(20.dp))
                    }
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
                        // Task mode and task colour have one visible entry in the editor row.
                        if (!viewModel.isTask) {
                            DropdownMenuItem(text = { Text(uiText("Цвет заметки")) },
                                leadingIcon = { Icon(Icons.Default.Palette, null) }, onClick = { ui.dismissMenus(); ui.showColorDialog = true })
                        }
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
