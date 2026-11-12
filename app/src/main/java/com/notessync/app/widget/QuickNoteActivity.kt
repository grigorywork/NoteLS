package com.notessync.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    val date = remember(dayKey) { LocalDate.ofEpochDay(dayKey) }
    var text by remember { mutableStateOf("") }
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
                taskError = "Не удалось сохранить отметку. Попробуйте ещё раз."
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
                taskError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(dayNotes, key = { it.id }) { note ->
                        val style = settings.widgetCompletedStyle
                        val checked = note.isTask && note.isCompleted
                        val enabled = note.id !in pendingIds
                        val title = note.title.ifBlank { "Без названия" }
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
                                    else "Отметить как выполненную задачу: $title"
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

