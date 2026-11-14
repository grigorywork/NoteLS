package com.notessync.app.ui.editor

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.tasks.TaskTimerIndicator
import com.notessync.app.ui.tasks.CountdownIndicator
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.settings.SettingsState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditorTaskControls(viewModel: NoteEditorViewModel, ui: EditorUiState, settingsState: SettingsState, onExit: () -> Unit) {
    if (viewModel.noteTags.isNotEmpty()) {
        FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        viewModel.noteTags.forEach { tag ->
            InputChip(
                selected = false,
                onClick = { viewModel.removeTag(tag) },
                label = { Text(tag) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = uiText("Убрать тег")) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        }
    }
    FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FilterChip(
            selected = viewModel.isTask,
            enabled = !viewModel.taskProtectionBusy && !viewModel.isClosing,
            onClick = viewModel::toggleTask,
            label = { Text(uiText("Задача")) },
            leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
            modifier = Modifier.padding(end = 8.dp)
        )
            AssistChip(
                onClick = { ui.showColorDialog = true },
                label = { Text(if (!viewModel.isTask && viewModel.noteColor.isEmpty()) uiText("Цвет") else taskColorOption(if (viewModel.isTask) viewModel.taskColor else viewModel.noteColor).label) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        // Этап 35: насыщенный акцент вместо бледной пастели.
                        tint = taskColorOption(if (viewModel.isTask) viewModel.taskColor else viewModel.noteColor).accent
                    )
                },
                modifier = Modifier.padding(end = 8.dp)
            )
        if (viewModel.isTask) {
            FilterChip(
                selected = viewModel.isCompleted,
                onClick = viewModel::toggleCompleted,
                label = { Text(if (viewModel.isCompleted) uiText("Выполнено") else uiText("Выполнить")) },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                modifier = Modifier.padding(end = 8.dp)
            )
            AssistChip(
                onClick = viewModel::toggleTaskTimer,
                enabled = !viewModel.isCompleted || viewModel.taskTimerStartedAt != null,
                label = {
                    Text(
                        when {
                            viewModel.taskTimerStartedAt != null -> uiText("Пауза")
                            viewModel.taskElapsedMillis > 0L -> uiText("Продолжить")
                            else -> uiText("Запустить")
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        if (viewModel.taskTimerStartedAt != null) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                },
                modifier = Modifier.padding(end = 8.dp)
            )
            // Этап 30: обратный отсчёт с шаблонами и своим временем.
            Box {
                val countdownActive = viewModel.taskCountdownMillis > 0L ||
                    viewModel.taskCountdownEndAt != null
                AssistChip(
                    onClick = { ui.showCountdownMenu = true },
                    enabled = !viewModel.isCompleted || countdownActive,
                    label = { Text(uiText("Таймер")) },
                    leadingIcon = {
                        Icon(Icons.Default.Timer, contentDescription = null)
                    }
                )
                DropdownMenu(
                    expanded = ui.showCountdownMenu,
                    onDismissRequest = { ui.showCountdownMenu = false }
                ) {
                    countdownPresets.forEach { (label, millis) ->
                        DropdownMenuItem(
                            text = { Text(uiText(label)) },
                            onClick = {
                                ui.showCountdownMenu = false
                                viewModel.startCountdown(millis)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(uiText("Своё время…")) },
                        onClick = {
                            ui.showCountdownMenu = false
                            ui.showCountdownCustomDialog = true
                        }
                    )
                    DropdownMenuItem(text = { Text(uiText("До времени…")) }, onClick = { ui.showCountdownMenu = false; ui.showCountdownUntil = true })
                    if (countdownActive) {
                        if (viewModel.taskCountdownEndAt != null) {
                            DropdownMenuItem(
                                text = { Text(uiText("Пауза")) },
                                onClick = {
                                    ui.showCountdownMenu = false
                                    viewModel.pauseCountdown()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(uiText("Продолжить")) },
                                onClick = {
                                    ui.showCountdownMenu = false
                                    viewModel.resumeCountdown()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(uiText("Снять таймер")) },
                            onClick = {
                                ui.showCountdownMenu = false
                                viewModel.cancelCountdown()
                            }
                        )
                    }
                }
            }
            Box {
                AssistChip(
                    onClick = {
                        if (viewModel.isProtected) ui.showTaskProtectionMenu = true
                        else ui.showTaskPasswordDialog = true
                    },
                    label = { Text(if (viewModel.isProtected) uiText("Защищено") else uiText("Пароль")) },
                    leadingIcon = {
                        Icon(
                            if (viewModel.isProtected) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenu(
                    expanded = ui.showTaskProtectionMenu,
                    onDismissRequest = { ui.showTaskProtectionMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(uiText("Заблокировать и закрыть")) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = {
                            ui.showTaskProtectionMenu = false
                            viewModel.lockTask()
                            onExit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(uiText("Снять защиту")) },
                        leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                        onClick = {
                            ui.showTaskProtectionMenu = false
                            ui.taskProtectionError = false
                            ui.showRemoveTaskProtection = true
                        }
                    )
                }
            }
        }
    }
    if (viewModel.isTask) Box(Modifier.padding(horizontal = 16.dp)) {
        com.notessync.app.ui.tasks.TaskDeadlinePicker(viewModel.taskDueAt, viewModel.isReady && !viewModel.isClosing, viewModel::chooseTaskDeadline)
    }
    if (viewModel.isTask && settingsState.showTaskPriorities) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            com.notessync.app.ui.tasks.TaskPriorityPicker(viewModel.taskPriority, viewModel::choosePriority)
        }
    }
    if (viewModel.isTask) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskTimerIndicator(
                elapsedMillis = viewModel.taskElapsedMillis,
                startedAt = viewModel.taskTimerStartedAt,
                contentColor = taskColorOption(viewModel.taskColor).accent,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { ui.showTimerResetDialog = true },
                enabled = viewModel.taskElapsedMillis > 0L || viewModel.taskTimerStartedAt != null
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Text(uiText("Сбросить"))
            }
        }
        // Этап 30: строка обратного отсчёта (когда таймер задан).
        if (viewModel.taskCountdownMillis > 0L || viewModel.taskCountdownEndAt != null) {
            CountdownIndicator(
                countdownMillis = viewModel.taskCountdownMillis,
                endAt = viewModel.taskCountdownEndAt,
                contentColor = taskColorOption(viewModel.taskColor).accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
    viewModel.reminderAt?.let { at ->
        InputChip(
            selected = true,
            onClick = { ui.showDatePicker = true },
            label = { Text(uiText("Напомнить: ") + formatDateTime(at)) },
            leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = viewModel::clearReminder) {
                    Icon(Icons.Default.Close, contentDescription = uiText("Убрать напоминание"))
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
private val countdownPresets: List<Pair<String, Long>> = listOf(
    "10 минут" to 10 * 60_000L,
    "15 минут" to 15 * 60_000L,
    "30 минут" to 30 * 60_000L,
    "1 час" to 60 * 60_000L,
    "2 часа" to 2 * 60 * 60_000L
)
