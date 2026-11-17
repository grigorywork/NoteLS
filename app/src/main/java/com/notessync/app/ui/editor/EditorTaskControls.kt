package com.notessync.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsState
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.theme.LocalGlassTheme

@Composable
internal fun EditorTaskControls(viewModel: NoteEditorViewModel, ui: EditorUiState, settingsState: SettingsState) {
    val enabled = viewModel.isReady && !viewModel.isClosing && !viewModel.taskProtectionBusy
    if (!viewModel.isTask) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TaskModeButton(viewModel, ui, enabled)
                viewModel.notebookName?.let { name -> TextButton(onClick = { ui.showNotebookMenu = true }, enabled = enabled) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                } }
            }
        }
        return
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp).testTag("editor-task-row"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::toggleCompleted, enabled = enabled, modifier = Modifier.size(44.dp)) {
                Icon(if (viewModel.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    uiText(if (viewModel.isCompleted) "Выполнено" else "Выполнить"), Modifier.size(20.dp),
                    tint = if (viewModel.isCompleted) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            IconButton(onClick = { ui.dismissMenus(); ui.showColorDialog = true }, enabled = enabled,
                modifier = Modifier.size(44.dp).testTag("editor-task-color")) {
                Icon(Icons.Default.ChangeHistory, uiText("Цвет задачи"), Modifier.size(18.dp),
                    tint = taskColorOption(viewModel.taskColor).accent)
            }
            TaskModeButton(viewModel, ui, enabled)
            IconButton(onClick = viewModel::toggleTaskTimer,
                enabled = enabled && (!viewModel.isCompleted || viewModel.taskTimerStartedAt != null),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("editor-stopwatch")) {
                TaskTimerIndicator(viewModel.taskElapsedMillis, viewModel.taskTimerStartedAt, compact = true,
                    icon = if (viewModel.taskTimerStartedAt != null) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentColor = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.weight(1f)) {
                val active = viewModel.taskCountdownMillis > 0 || viewModel.taskCountdownEndAt != null
                IconButton(onClick = { ui.showCountdownMenu = true }, enabled = enabled && (!viewModel.isCompleted || active),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("editor-countdown")) {
                    if (active) CountdownIndicator(viewModel.taskCountdownMillis, viewModel.taskCountdownEndAt,
                        compact = true, contentColor = MaterialTheme.colorScheme.primary)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.HourglassEmpty, uiText("Таймер"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("00:00", style = MaterialTheme.typography.labelMedium)
                    }
                }
                CountdownMenu(viewModel, ui)
            }
            Box {
                IconButton(onClick = { ui.popup = if (ui.popup == EditorPopup.TASK) null else EditorPopup.TASK },
                    enabled = enabled, modifier = Modifier.size(44.dp)) {
                    Icon(if (ui.popup == EditorPopup.TASK) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        uiText("Параметры задачи"), Modifier.size(20.dp))
                }
                DropdownMenu(ui.popup == EditorPopup.TASK, { ui.dismissMenus() }) {
                    DropdownMenuItem(text = { Text(viewModel.taskDueAt?.let { formatDateTime(it) } ?: uiText("Срок задачи")) },
                        leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(20.dp)) },
                        onClick = { ui.dismissMenus(); ui.showDeadlinePicker = true })
                    if (settingsState.showTaskPriorities) DropdownMenuItem(text = { Text(TaskPriority.from(viewModel.taskPriority).label) },
                        leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(20.dp), tint = priorityColor(viewModel.taskPriority)) },
                        onClick = { ui.dismissMenus(); ui.showPriorityMenu = true })
                    DropdownMenuItem(text = { Text(uiText("Сбросить секундомер")) }, enabled = viewModel.taskElapsedMillis > 0 || viewModel.taskTimerStartedAt != null,
                        leadingIcon = { Icon(Icons.Default.RestartAlt, null, Modifier.size(20.dp)) },
                        onClick = { ui.dismissMenus(); ui.showTimerResetDialog = true })
                }
                DropdownMenu(ui.showPriorityMenu, { ui.showPriorityMenu = false }) {
                    TaskPriority.entries.forEach { priority -> DropdownMenuItem(text = { Text(priority.label) },
                        leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(20.dp), tint = priorityColor(priority.value)) },
                        onClick = { ui.showPriorityMenu = false; viewModel.choosePriority(priority.value) }) }
                }
            }
        }
    }
    if (ui.showDeadlinePicker) TaskDeadlineDialog(viewModel.taskDueAt, enabled, viewModel::chooseTaskDeadline,
        onDismiss = { ui.showDeadlinePicker = false })
}

/** One persistent checkbox, including ordinary notes; enabled task mode is green. */
@Composable
private fun TaskModeButton(viewModel: NoteEditorViewModel, ui: EditorUiState, enabled: Boolean) {
    val green = if (LocalGlassTheme.current.dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    IconToggleButton(checked = viewModel.isTask,
        onCheckedChange = { ui.dismissMenus(); viewModel.toggleTask() }, enabled = enabled,
        modifier = Modifier.size(44.dp).testTag("editor-task-toggle"),
        colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = green)) {
        Icon(Icons.Default.CheckBox, uiText(if (viewModel.isTask) "Сделать заметкой" else "Сделать задачей"), Modifier.size(18.dp))
    }
}

@Composable
private fun CountdownMenu(viewModel: NoteEditorViewModel, ui: EditorUiState) {
    val active = viewModel.taskCountdownMillis > 0 || viewModel.taskCountdownEndAt != null
    DropdownMenu(ui.showCountdownMenu, { ui.showCountdownMenu = false }) {
        listOf("10 минут" to 10, "15 минут" to 15, "30 минут" to 30, "1 час" to 60, "2 часа" to 120).forEach { (label, minutes) ->
            DropdownMenuItem(text = { Text(uiText(label)) }, onClick = { ui.dismissMenus(); viewModel.startCountdown(minutes * 60_000L) })
        }
        DropdownMenuItem(text = { Text(uiText("Своё время…")) }, onClick = { ui.dismissMenus(); ui.showCountdownCustomDialog = true })
        DropdownMenuItem(text = { Text(uiText("До времени…")) }, onClick = { ui.dismissMenus(); ui.showCountdownUntil = true })
        if (active) {
            DropdownMenuItem(text = { Text(uiText(if (viewModel.taskCountdownEndAt != null) "Пауза" else "Продолжить")) },
                onClick = { ui.dismissMenus(); if (viewModel.taskCountdownEndAt != null) viewModel.pauseCountdown() else viewModel.resumeCountdown() })
            DropdownMenuItem(text = { Text(uiText("Снять таймер")) }, onClick = { ui.dismissMenus(); viewModel.cancelCountdown() })
        }
    }
}
