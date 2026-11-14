package com.notessync.app.ui.editor

import com.notessync.app.i18n.uiText
import com.notessync.app.ui.notes.audio.*
import android.content.Intent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notessync.app.ui.notes.tables.AddTableDialog
import com.notessync.app.ui.notes.tables.TableEditorDialog
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.reminders.ReminderTime
import com.notessync.app.ui.common.SetPasswordDialog
import com.notessync.app.ui.common.UnlockPasswordDialog
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.photos.PhotoPreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.notessync.app.ui.tasks.taskColorOptions
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import com.notessync.app.settings.SettingsState
import com.notessync.app.data.local.entity.AttachmentEntity
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditorDialogs(viewModel: NoteEditorViewModel, ui: EditorUiState, settingsState: SettingsState,
    attachments: List<AttachmentEntity>, snackbarHostState: SnackbarHostState, scope: CoroutineScope, onDelete: () -> Unit) {
    val context = LocalContext.current
    val visiblePhoto = attachments.firstOrNull { it.id == ui.openPhoto }
    if (visiblePhoto != null) PhotoPreviewDialog(visiblePhoto, settingsState.photoResize, snackbarHostState,
        onSave = { viewModel.savePhotoSize(visiblePhoto, it) },
        onOriginal = { scope.launch { openAttachment(context, visiblePhoto.filePath, visiblePhoto.mimeType) } },
        onDismiss = { ui.openPhoto = null })

    if (ui.showAddTable) AddTableDialog(onDismiss = { ui.showAddTable = false },
        remainingCells = com.notessync.app.notes.tables.NoteTables.MAX_CELLS - (viewModel.tables?.cellCount ?: 0),
        remainingTables = com.notessync.app.notes.tables.NoteTables.MAX_TABLES - (viewModel.tables?.blocks?.size ?: 0)) { rows, columns ->
        viewModel.addTable(rows, columns)?.let { ui.openTable = it; ui.showAddTable = false }
    }
    val visibleTable = ui.openTable
    if (visibleTable != null && settingsState.showTables) TableEditorDialog(visibleTable, viewModel,
        snackbarHostState = snackbarHostState, onDismiss = { ui.openTable = null })

    if (ui.showTimerResetDialog) {
        AlertDialog(
            onDismissRequest = { ui.showTimerResetDialog = false },
            title = { Text(uiText("Сбросить таймер?")) },
            text = { Text(uiText("Зафиксированное время будет удалено.")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTaskTimer()
                    ui.showTimerResetDialog = false
                }) { Text(uiText("Сбросить")) }
            },
            dismissButton = {
                TextButton(onClick = { ui.showTimerResetDialog = false }) { Text(uiText("Отмена")) }
            }
        )
    }

    if (ui.showCountdownUntil) com.notessync.app.ui.tasks.CountdownUntilDialog(
        onDismiss = { ui.showCountdownUntil = false },
        onStart = { target -> viewModel.startCountdownUntil(target) }
    )

    if (ui.showCountdownCustomDialog) {
        var hoursText by remember { mutableStateOf("") }
        var minutesText by remember { mutableStateOf("") }
        val hours = hoursText.toLongOrNull() ?: 0L
        val minutes = minutesText.toLongOrNull() ?: 0L
        val totalMillis = (hours * 60L + minutes) * 60_000L
        AlertDialog(
            onDismissRequest = { ui.showCountdownCustomDialog = false },
            title = { Text(uiText("Своё время")) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { input -> hoursText = input.filter(Char::isDigit).take(2) },
                        label = { Text(uiText("Часы")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { input -> minutesText = input.filter(Char::isDigit).take(2) },
                        label = { Text(uiText("Минуты")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.startCountdown(totalMillis)
                        ui.showCountdownCustomDialog = false
                    },
                    enabled = totalMillis > 0L
                ) { Text(uiText("Запустить")) }
            },
            dismissButton = {
                TextButton(onClick = { ui.showCountdownCustomDialog = false }) { Text(uiText("Отмена")) }
            }
        )
    }

    if (ui.showColorDialog) {
        AlertDialog(
            onDismissRequest = { ui.showColorDialog = false },
            title = { Text(if (viewModel.isTask) uiText("Цвет задачи") else uiText("Цвет заметки")) },
            text = {
                FlowRow {
                    if (!viewModel.isTask) FilterChip(selected = viewModel.noteColor.isEmpty(), onClick = {
                        viewModel.chooseTaskColor(""); ui.showColorDialog = false
                    }, label = { Text(uiText("По теме")) }, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp))
                    taskColorOptions.forEach { option ->
                        FilterChip(
                            selected = (if (viewModel.isTask) viewModel.taskColor else viewModel.noteColor) == option.key,
                            onClick = {
                                viewModel.chooseTaskColor(option.key)
                                ui.showColorDialog = false
                            },
                            label = { Text(option.label) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = option.accent
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ui.showColorDialog = false }) { Text(uiText("Закрыть")) }
            }
        )
    }

    val protectionLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(viewModel, protectionLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.cancelTaskProtection()
        }
        protectionLifecycle.addObserver(observer)
        onDispose { protectionLifecycle.removeObserver(observer); viewModel.cancelTaskProtection() }
    }

    if (ui.showTaskPasswordDialog) {
        SetPasswordDialog(
            title = uiText("Пароль задачи"),
            onDismiss = { ui.showTaskPasswordDialog = false; viewModel.cancelTaskProtection() },
            onConfirm = { password ->
                viewModel.protectTask(password)
                ui.showTaskPasswordDialog = false
            }
        )
    }

    if (ui.showRemoveTaskProtection) {
        UnlockPasswordDialog(
            title = uiText("Снять защиту задачи"),
            showError = ui.taskProtectionError,
            busy = viewModel.taskProtectionBusy,
            onDismiss = { ui.showRemoveTaskProtection = false; viewModel.cancelTaskProtection() },
            onConfirm = { password ->
                viewModel.removeTaskProtection(password) { verified ->
                    if (verified) ui.showRemoveTaskProtection = false else ui.taskProtectionError = true
                }
            }
        )
    }

    if (ui.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { ui.showDeleteDialog = false },
            title = { Text(uiText("Удалить заметку?")) },
            text = {
                Text(
                    viewModel.title.ifBlank { uiText("Без названия") },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                }) { Text(uiText("Удалить")) }
            },
            dismissButton = {
                TextButton(onClick = { ui.showDeleteDialog = false }) { Text(uiText("Отмена")) }
            }
        )
    }

    if (ui.showTagDialog) {
        var tagName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ui.showTagDialog = false },
            title = { Text(uiText("Новый тег")) },
            text = {
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = { Text(uiText("Название")) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.addTag(tagName); ui.showTagDialog = false },
                    enabled = tagName.isNotBlank()
                ) { Text(uiText("Добавить")) }
            },
            dismissButton = {
                TextButton(onClick = { ui.showTagDialog = false }) { Text(uiText("Отмена")) }
            }
        )
    }

    if (ui.showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = viewModel.reminderAt ?: System.currentTimeMillis() + 3_600_000L
        )
        DatePickerDialog(
            onDismissRequest = { ui.showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    ui.showDatePicker = false
                    dateState.selectedDateMillis?.let { ui.showTimePickerFor = it }
                }) { Text(uiText("Далее")) }
            },
            dismissButton = {
                TextButton(onClick = { ui.showDatePicker = false }) { Text(uiText("Отмена")) }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    ui.showTimePickerFor?.let { dateMillis ->
        val calendar = Calendar.getInstance().apply {
            timeInMillis = viewModel.reminderAt ?: System.currentTimeMillis() + 3_600_000L
        }
        val timeState = rememberTimePickerState(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        AlertDialog(
            onDismissRequest = { ui.showTimePickerFor = null },
            title = { Text(uiText("Время")) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminder(
                        ReminderTime.combineDateAndTime(dateMillis, timeState.hour, timeState.minute)
                    )
                    ui.showTimePickerFor = null
                }) { Text(uiText("Готово")) }
            },
            dismissButton = {
                TextButton(onClick = { ui.showTimePickerFor = null }) { Text(uiText("Отмена")) }
            }
        )
    }
}
private suspend fun openAttachment(context: android.content.Context, path: String, mime: String) {
    runCatching {
        val uri = withContext(Dispatchers.IO) { FileProvider.getUriForFile(context, "com.notessync.app.fileprovider", File(path)) }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}
