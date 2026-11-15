package com.notessync.app.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.notessync.app.data.local.model.NoteListEntry
import com.notessync.app.i18n.uiText
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.delay
import java.time.*

@Composable
internal fun TaskDeadlinePicker(value: Long?, enabled: Boolean, onChange: (Long?) -> Unit) =
    TaskDeadlinePickerContent(value, enabled, onChange)

@Composable
internal fun TaskDeadlineDialog(value: Long?, enabled: Boolean, onChange: (Long?) -> Unit, onDismiss: () -> Unit) =
    TaskDeadlinePickerContent(value, enabled, onChange, showTrigger = false, onDismiss = onDismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDeadlinePickerContent(value: Long?, enabled: Boolean, onChange: (Long?) -> Unit,
    showTrigger: Boolean = true, onDismiss: () -> Unit = {}) {
    var step by rememberSaveable { mutableIntStateOf(if (showTrigger) 0 else 1) }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val zone = ZoneId.systemDefault()
    fun close() { step = 0; onDismiss() }
    if (showTrigger) AssistChip(onClick = { step = 1 }, enabled = enabled,
        label = { Text(value?.let { uiText("Срок: %1\$s", formatDateTime(it)) } ?: uiText("Срок задачи")) },
        leadingIcon = { Icon(Icons.Default.Event, null) })
    if (step == 1) {
        val initial = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now()
        val bounded = initial.coerceIn(LocalDate.of(1900, 1, 1), LocalDate.of(2100, 12, 31))
        val picker = rememberDatePickerState(initialSelectedDateMillis = bounded.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1900..2100)
        DatePickerDialog(onDismissRequest = { close() }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(); step = 2 } }) { Text(uiText("Далее")) }
        }, dismissButton = {
            Row { if (value != null) TextButton(onClick = { onChange(null); close() }) { Text(uiText("Убрать срок")) }
                TextButton(onClick = { close() }) { Text(uiText("Отмена")) } }
        }) { DatePicker(picker) }
    }
    if (step == 2) {
        val time = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(23, 59)
        val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { close() }, title = { Text(uiText("Время срока")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiText("Срок не включает напоминание или таймер."), style = MaterialTheme.typography.bodySmall)
                TimeInput(picker)
            } }, confirmButton = { TextButton(onClick = {
                onChange(LocalDate.ofEpochDay(day).atTime(picker.hour, picker.minute).atZone(zone).toInstant().toEpochMilli()); close()
            }) { Text(uiText("Готово")) } }, dismissButton = { TextButton(onClick = { close() }) { Text(uiText("Отмена")) } })
    }
}

@Composable
internal fun TaskDeadlineLabel(at: Long?, now: Long, completed: Boolean, color: Color = LocalContentColor.current) {
    if (at == null) return
    val overdue = !completed && at < now
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val tint = if (overdue) MaterialTheme.colorScheme.error else color
        Icon(Icons.Default.Event, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(uiText(if (overdue) "Просрочено: %1\$s" else "Срок: %1\$s", formatDateTime(at)), color = tint,
            style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

/** One foreground boundary wake-up; no clock per card and no periodic/background job. */
@Composable
internal fun TaskDeadlineClock(viewModel: NotesViewModel, filter: NoteFilter, notes: List<NoteListEntry>) {
    val now by viewModel.taskClock.collectAsStateWithLifecycle()
    val nextDue by viewModel.nextTaskDue.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LifecycleStartEffect(viewModel, context) {
        viewModel.refreshTaskClock()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) { viewModel.refreshTaskClock() }
        }
        val events = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED); addAction(android.content.Intent.ACTION_TIME_CHANGED); addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Only protected system clock broadcasts; also delivered on Android versions before33.
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, events, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onStopOrDispose { context.unregisterReceiver(receiver); viewModel.markListHidden() }
    }
    val nearest = if (filter.isUnfinished()) nextDue else remember(notes, now) {
        notes.asSequence().filter { it.isTask && !it.isCompleted }.mapNotNull { it.taskDueAt }.filter { it >= now }.minOrNull()
    }
    LaunchedEffect(lifecycle, filter, now, nearest) {
        if (!filter.isUnfinished() && nearest == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val current = System.currentTimeMillis()
            val target = TaskDeadlines.nextRefresh(current, nearest, ZoneId.systemDefault(), capturedAt = now)
            delay((target - current).coerceAtLeast(1))
            viewModel.refreshTaskClock()
        }
    }
}
