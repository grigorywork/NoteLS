package com.notessync.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.ui.tasks.TaskCompletion
import com.notessync.app.ui.theme.GlassPanelShape
import com.notessync.app.ui.theme.LocalGlassTheme
import com.notessync.app.ui.theme.NoteSyncTheme
import com.notessync.app.ui.theme.glassBorder
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Быстрая заметка к конкретному дню (Этап 8).
 *  Открывается тапом по дате в виджете; показывает список заметок этого дня. */
class QuickNoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dayKey = intent.getLongExtra(
            CalendarWidgetProvider.EXTRA_DAY_KEY,
            LocalDate.now().toEpochDay()
        )
        setContent {
            val accent by com.notessync.app.settings.SettingsRepository.get(this)
                .state.collectAsStateWithLifecycle()
            NoteSyncTheme(
                accentColor = accent.accentColor,
                appFont = accent.appFont,
                interfaceStyle = accent.interfaceStyle,
                glassBackdrop = false
            ) {
                QuickNoteContent(
                    dayKey = dayKey,
                    onClose = { finish() },
                    onSaved = {
                        // Обновляем маркеры на виджете после сохранения.
                        CalendarWidgetProvider.refreshAll(applicationContext)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickNoteContent(
    dayKey: Long,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { AppDatabase.get(context).noteDao() }
    val date = remember { LocalDate.ofEpochDay(dayKey) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val dayNotes by remember {
        dao.observeByDay(dayKey)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    fun toggleDayTask(note: NoteEntity) {
        if (!note.isTask) return
        scope.launch {
            val updated = TaskCompletion.toggle(note)
            dao.update(updated)
            if (updated.isCompleted) {
                ReminderScheduler.cancel(context, note.id)
                ReminderScheduler.cancelTimer(context, note.id)
            }
            CalendarWidgetProvider.refreshAll(context)
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
        Column(modifier = Modifier.padding(20.dp)) {
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
            Text(
                text = "$weekday, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))}",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Быстрая заметка") },
                minLines = 3
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val content = text.trim()
                        if (content.isEmpty()) return@Button
                        scope.launch {
                            dao.upsert(
                                NoteEntity(
                                    title = content.lineSequence().first().take(60),
                                    content = content,
                                    dayKey = dayKey
                                )
                            )
                            BackupManager.noteChanged()
                            BackupManager.requestAutoBackup(context, afterNote = true)
                            onSaved()
                        }
                    }
                ) { Text("Сохранить") }
                OutlinedButton(onClick = onClose) { Text("Закрыть") }
            }

            if (dayNotes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Заметки и задачи этого дня",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Этап 36: стиль выполненных задач настраивается в меню ⋮ виджета.
                val settings by com.notessync.app.settings.SettingsRepository.get(context)
                    .state.collectAsStateWithLifecycle()
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(dayNotes, key = { it.id }) { note ->
                        val green = com.notessync.app.settings.WidgetCompletedStyle.GREEN
                        val strike = com.notessync.app.settings.WidgetCompletedStyle.STRIKE
                        val both = com.notessync.app.settings.WidgetCompletedStyle.BOTH
                        val style = settings.widgetCompletedStyle
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (note.isTask) {
                                androidx.compose.material3.Checkbox(
                                    checked = note.isCompleted,
                                    onCheckedChange = { toggleDayTask(note) },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                                        uncheckedColor = com.notessync.app.ui.tasks
                                            .taskColorOption(note.taskColor).accent
                                    )
                                )
                            }
                            Text(
                                text = note.title.ifBlank { "Без названия" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (note.isCompleted && (style == green || style == both)) {
                                    androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                } else {
                                    androidx.compose.ui.graphics.Color.Unspecified
                                },
                                textDecoration = if (
                                    note.isCompleted && (style == strike || style == both)
                                ) {
                                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                                } else {
                                    androidx.compose.ui.text.style.TextDecoration.None
                                },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
