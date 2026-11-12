package com.notessync.app.widget

import com.notessync.app.i18n.uiText

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.ui.theme.GlassPanelShape
import com.notessync.app.ui.theme.LocalGlassTheme
import com.notessync.app.ui.theme.NoteSyncTheme
import com.notessync.app.ui.theme.glassBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Быстрая заметка к конкретному дню (Этап 8).
 *  Открывается тапом по дате в виджете; показывает список заметок этого дня. */
class QuickNoteActivity : ComponentActivity() {
    private val quick: QuickTaskViewModel by viewModels()

    companion object {
        const val EXTRA_QUICK_ADD = "quickAdd"
        fun intent(context: Context, dayKey: Long, startWithTime: Boolean = true) =
            Intent(context, QuickNoteActivity::class.java)
                .putExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, dayKey)
                .putExtra(EXTRA_QUICK_ADD, startWithTime)
    }

    override fun onStop() {
        quick.onStopped()
        val widgetId = intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) CalendarWidgetProvider.selectDay(this, widgetId, quick.dayKey)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContent {
            val accent by com.notessync.app.settings.SettingsRepository.get(this)
                .state.collectAsStateWithLifecycle()
            NoteSyncTheme(
                darkTheme = when (accent.themeMode) {
                    com.notessync.app.settings.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    com.notessync.app.settings.ThemeMode.LIGHT -> false
                    com.notessync.app.settings.ThemeMode.DARK -> true
                },
                fontScale = accent.fontScale,
                accentColor = accent.accentColor,
                appFont = accent.appFont,
                interfaceStyle = accent.interfaceStyle,
                glassBackdrop = false
            ) {
                QuickNoteContent(viewModel = quick, startWithTime = intent.getBooleanExtra(EXTRA_QUICK_ADD, false),
                    onClose = { quick.close { finish() } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickNoteContent(
    viewModel: QuickTaskViewModel,
    startWithTime: Boolean,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { AppDatabase.get(context).noteDao() }
    val dayKey = viewModel.dayKey
    val date = remember(dayKey) { LocalDate.ofEpochDay(dayKey) }
    val settings by com.notessync.app.settings.SettingsRepository.get(context).state.collectAsStateWithLifecycle()
    var showTime by rememberSaveable { mutableStateOf(startWithTime && settings.quickTaskTime) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var permissionTime by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler { onClose() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val at = permissionTime; permissionTime = null
        if (granted && at != null) viewModel.setReminder(at) else viewModel.notificationDenied()
    }
    if (showTime) {
        val initial = viewModel.reminderAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        val now = java.time.LocalDateTime.now()
        val suggested = if (date == now.toLocalDate()) now.plusMinutes(30).let {
            if (it.toLocalDate() == date) it.toLocalTime() else java.time.LocalTime.of(23, 59)
        } else java.time.LocalTime.of(9, 0)
        val time = rememberTimePickerState(initialHour = initial?.hour ?: suggested.hour, initialMinute = initial?.minute ?: suggested.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { showTime = false }, title = { Text(uiText("Время напоминания")) },
            text = { TimeInput(time) }, confirmButton = {
                TextButton(onClick = {
                    val at = date.atTime(time.hour, time.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    showTime = false
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permissionTime = at; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.setReminder(at)
                }) { Text(uiText("Готово")) }
            }, dismissButton = { TextButton(onClick = { viewModel.setReminder(null); showTime = false }) { Text(uiText("Без напоминания")) } })
    }
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { viewModel.changeDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                showDate = false
            }) { Text(uiText("Готово")) }
        }, dismissButton = { TextButton(onClick = { showDate = false }) { Text(uiText("Отмена")) } }) { DatePicker(picker) }
    }
    val scope = rememberCoroutineScope()
    val dayNotes by remember(dao, dayKey) {
        dao.observeByDay(dayKey)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingIds by remember { mutableStateOf(emptySet<Long>()) }
    var taskError by remember { mutableStateOf<String?>(null) }

    fun setDayTaskCompleted(note: NoteEntity, completed: Boolean) {
        if (note.id in pendingIds) return
        pendingIds = pendingIds + note.id
        taskError = null
        scope.launch {
            try {
                DayTaskActions.setCompleted(context, note.id, dayKey, completed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                taskError = uiText("Не удалось сохранить отметку. Попробуйте ещё раз.")
            } finally {
                pendingIds = pendingIds - note.id
            }
        }
    }

    // Этап 37: в режиме стекла панель парит над рабочим столом с кромкой и крупным скруглением.
    val glass = LocalGlassTheme.current
    Card(
        shape = if (glass.enabled) GlassPanelShape else MaterialTheme.shapes.medium,
        modifier = Modifier
            .padding(16.dp)
            .imePadding()
            .glassBorder()
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", com.notessync.app.i18n.UiStrings.locale)),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                IconButton(onClick = { viewModel.next { showTime = settings.quickTaskTime } }, enabled = !viewModel.busy) {
                    Icon(Icons.Default.Add, uiText("Сохранить и новая задача"))
                }
                IconButton(onClick = onClose, enabled = !viewModel.busy) { Icon(Icons.Default.Close, uiText("Закрыть")) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = viewModel.text,
                onValueChange = viewModel::changeText,
                enabled = !viewModel.busy,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(uiText("Быстрая задача")) },
                minLines = 2, maxLines = 5
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AssistChip(onClick = { showDate = true }, enabled = !viewModel.busy, label = { Text(uiText("Дата")) })
                AssistChip(onClick = { showTime = true }, enabled = !viewModel.busy,
                    label = { Text(viewModel.reminderAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: uiText("Время")) },
                    leadingIcon = { Icon(Icons.Default.Alarm, null) })
            }
            if (viewModel.reminderAt != null) TextButton(onClick = { viewModel.setReminder(null) }, enabled = !viewModel.busy) { Text(uiText("Без напоминания")) }
            Text(viewModel.error ?: if (viewModel.text.isBlank()) uiText("Пустые задачи не сохраняются") else if (viewModel.isSaved) uiText("Сохранено") else uiText("Сохраняется…"),
                color = if (viewModel.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (viewModel.error != null) TextButton(onClick = { scope.launch { viewModel.persist() } }, enabled = !viewModel.busy) { Text(uiText("Повторить")) }
            }

            if (dayNotes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = uiText("Заметки и задачи этого дня"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Этап 36: стиль выполненных задач настраивается в меню ⋮ виджета.
                taskError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(dayNotes, key = { it.id }) { note ->
                        val style = settings.widgetCompletedStyle
                        val checked = note.isTask && note.isCompleted
                        val enabled = note.id !in pendingIds
                        val title = note.title.ifBlank { uiText("Без названия") }
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(
                                    value = checked,
                                    enabled = enabled,
                                    role = Role.Checkbox,
                                    onValueChange = { setDayTaskCompleted(note, it) }
                                )
                                .semantics {
                                    contentDescription = if (note.isTask) title
                                    else uiText("Отметить как выполненную задачу: %1\$s", (title))
                                }
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = enabled,
                                modifier = Modifier.padding(end = 12.dp),
                                colors = androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                                    uncheckedColor = com.notessync.app.ui.tasks
                                        .taskColorOption(note.taskColor).accent
                                )
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (checked && style.usesGreen) {
                                    androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                } else {
                                    androidx.compose.ui.graphics.Color.Unspecified
                                },
                                textDecoration = if (
                                    checked && style.usesStrike
                                ) {
                                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                                } else {
                                    androidx.compose.ui.text.style.TextDecoration.None
                                },
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

