package com.notessync.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.i18n.uiText
import com.notessync.app.notes.subtasks.Subtask
import com.notessync.app.settings.SettingsState
import com.notessync.app.tasks.running.RunningClockTime
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.tasks.TaskDurationStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** In the document scroll, never an overlay; small glyphs retain48dp touch targets. */
@Composable
internal fun EditorSubtasks(vm: NoteEditorViewModel, settings: SettingsState) {
    var expanded by remember { mutableStateOf(true) }
    var focusId by remember { mutableStateOf<String?>(null) }
    var timerId by remember { mutableStateOf<String?>(null) }
    var customTimer by remember { mutableStateOf<String?>(null) }
    val enabled = vm.isReady && !vm.isClosing && !vm.accessDenied && !vm.previewMode
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = expanded && vm.subtasks.any { it.startedAt != null || it.countdownEndAt != null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One shared foreground-only display tick for the visible group; persistence never follows this clock.
    LaunchedEffect(lifecycle, active) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = System.currentTimeMillis(); delay(1000) }
        }
    }
    val style = TaskDurationStyle(settings.automaticDurationUnits, settings.uiLanguage == UiLanguage.ENGLISH)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("editor-subtasks")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                Text(uiText("Подзадачи"), maxLines = 1)
                if (vm.subtasks.isNotEmpty()) Text(" ${vm.subtasks.count { it.completed }}/${vm.subtasks.size}", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { vm.addSubtask()?.let { focusId = it; expanded = true } }, enabled = enabled,
                modifier = Modifier.size(48.dp).testTag("add-subtask")) {
                Icon(Icons.Default.Add, uiText("Добавить подзадачу"), Modifier.size(18.dp))
            }
        }
        if (expanded) for (row in vm.subtasks) key(row.id) {
            val focus = remember { FocusRequester() }
            var more by remember { mutableStateOf(false) }
            LaunchedEffect(focusId == row.id) { if (focusId == row.id) { focus.requestFocus(); focusId = null } }
            Row(Modifier.fillMaxWidth().testTag("subtask-${row.id}"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.completed, onCheckedChange = if (enabled) ({ vm.toggleSubtask(row.id) }) else null,
                    modifier = Modifier.size(48.dp).testTag("subtask-check-${row.id}").semantics { contentDescription = uiText(if (row.completed) "Снять отметку подзадачи" else "Выполнить подзадачу") })
                TextField(value = row.text, onValueChange = { vm.editSubtask(row.id, it) },
                    readOnly = !enabled, maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (row.completed) TextDecoration.LineThrough else TextDecoration.None),
                    placeholder = { Text(uiText("Подзадача"), style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag("subtask-text-${row.id}"),
                    colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                if (!vm.previewMode) {
                    val elapsed = RunningClockTime.elapsed(row.elapsedMillis, row.startedAt, now)
                    IconButton(onClick = { vm.toggleSubtaskStopwatch(row.id) }, enabled = enabled && !row.completed && (!vm.isTask || !vm.isCompleted),
                        modifier = Modifier.size(48.dp).testTag("subtask-watch-${row.id}").semantics { stateDescription = style.format(elapsed) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (row.startedAt == null) Icons.Default.Timer else Icons.Default.Pause,
                                uiText(if (row.startedAt == null) "Запустить секундомер подзадачи" else "Пауза секундомера подзадачи"), Modifier.size(17.dp))
                            Text(style.format(elapsed, compact = true), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box {
                        val remaining = row.countdownEndAt?.let { RunningClockTime.remaining(it, now) } ?: row.countdownMillis
                        IconButton(onClick = { timerId = row.id }, enabled = enabled && !row.completed && (!vm.isTask || !vm.isCompleted),
                            modifier = Modifier.size(48.dp).testTag("subtask-timer-${row.id}").semantics { stateDescription = style.format(remaining) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.HourglassEmpty, uiText("Таймер подзадачи"), Modifier.size(17.dp))
                                Text(style.format(remaining, compact = true), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        DropdownMenu(expanded = timerId == row.id, onDismissRequest = { timerId = null }) {
                            for ((label, minutes) in listOf("1 минута" to 1, "5 минут" to 5, "10 минут" to 10, "15 минут" to 15, "30 минут" to 30, "1 час" to 60)) {
                                DropdownMenuItem(text = { Text(uiText(label)) }, onClick = { timerId = null; vm.startSubtaskCountdown(row.id, minutes * 60_000L) })
                            }
                            DropdownMenuItem(text = { Text(uiText("Своё время…")) }, onClick = { timerId = null; customTimer = row.id })
                            if (row.countdownEndAt != null) DropdownMenuItem(text = { Text(uiText("Пауза")) },
                                onClick = { timerId = null; vm.pauseSubtaskCountdown(row.id) })
                            else if (row.countdownMillis > 0) DropdownMenuItem(text = { Text(uiText("Продолжить")) },
                                onClick = { timerId = null; vm.resumeSubtaskCountdown(row.id) })
                            if (row.countdownMillis > 0 || row.countdownEndAt != null || row.countdownFinishedAt != null) DropdownMenuItem(text = { Text(uiText("Снять таймер")) },
                                onClick = { timerId = null; vm.clearSubtaskCountdown(row.id) })
                        }
                    }
                    Box {
                        IconButton(onClick = { more = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MoreVert, uiText("Параметры подзадачи"), Modifier.size(17.dp))
                        }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text(uiText("Удалить подзадачу")) }, onClick = { more = false; vm.removeSubtask(row.id) })
                        }
                    }
                } else {
                    Text(style.format(RunningClockTime.elapsed(row.elapsedMillis, row.startedAt, now), compact = true),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
    customTimer?.let { id ->
        var minutes by remember(id) { mutableStateOf("30") }
        val parsed = minutes.toLongOrNull()?.takeIf { it in 1..RunningClockTime.MAX_NATIVE_MILLIS / 60_000 }
        AlertDialog(onDismissRequest = { customTimer = null }, title = { Text(uiText("Таймер подзадачи")) },
            text = { OutlinedTextField(minutes, { minutes = it.take(8) }, label = { Text(uiText("Минуты")) },
                singleLine = true, isError = parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = { TextButton(enabled = parsed != null && enabled, onClick = { parsed?.let { vm.startSubtaskCountdown(id, it * 60_000) }; customTimer = null }) { Text(uiText("Запустить")) } },
            dismissButton = { TextButton(onClick = { customTimer = null }) { Text(uiText("Отмена")) } })
    }
    LaunchedEffect(enabled) { if (!enabled) { timerId = null; customTimer = null } }
}
