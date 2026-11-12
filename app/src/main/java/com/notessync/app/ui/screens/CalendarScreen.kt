package com.notessync.app.ui.screens

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.WidgetCompletedStyle
import com.notessync.app.ui.calendar.CalendarCell
import com.notessync.app.ui.calendar.CalendarViewModel
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.widget.WidgetDayEntry
import com.notessync.app.widget.WidgetDayPreview
import com.notessync.app.widget.WidgetPreviewLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Этап 36: тот же спокойный календарь, что и виджет, внутри приложения. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenNote: (Long, Boolean) -> Unit,
    onAddEntry: (Long, Boolean) -> Unit,
    viewModel: CalendarViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepository = remember { SettingsRepository.get(context) }
    val settings by settingsRepository.state.collectAsStateWithLifecycle()
    val weeks = remember(state.cells) { state.cells.chunked(7) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiText("Календарь")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("Назад"))
                    }
                },
                actions = {
                    IconButton(onClick = { onAddEntry(state.selectedDate.toEpochDay(), false) }) {
                        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = uiText("Новая заметка в полном редакторе"))
                    }
                    TextButton(onClick = viewModel::showToday) { Text(uiText("Сегодня")) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                context.startActivity(com.notessync.app.widget.QuickNoteActivity.intent(context, state.selectedDate.toEpochDay()))
            }) { Icon(Icons.Default.Add, contentDescription = uiText("Быстрая задача на выбранный день")) }
        }

    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::previousMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = uiText("Предыдущий месяц"))
                }
                Text(
                    text = monthTitle(state),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = viewModel::nextMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = uiText("Следующий месяц"))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                listOf(uiText("Пн"), uiText("Вт"), uiText("Ср"), uiText("Чт"), uiText("Пт"), uiText("Сб"), uiText("Вс")).forEach { weekday ->
                    Text(
                        text = weekday,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                weeks.forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { cell ->
                            CalendarDayCell(
                                cell = cell,
                                selected = cell.date == state.selectedDate,
                                today = cell.date == LocalDate.now(),
                                entries = state.notesByDay[cell.date.toEpochDay()].orEmpty(),
                                completedStyle = settings.widgetCompletedStyle,
                                coloredTasks = settings.widgetColoredTasks,
                                onClick = { viewModel.selectDate(cell.date) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                item {
                    Text(
                        text = selectedDayTitle(state.selectedDate),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
                    )
                }
                if (state.selectedNotes.isEmpty()) {
                    item {
                        Text(
                            text = uiText("На этот день записей нет.\nНажмите +, чтобы добавить заметку или задачу."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 24.dp)
                        )
                    }
                } else {
                    items(state.selectedNotes, key = { it.id }) { note ->
                        CalendarNoteRow(
                            note = note,
                            completedStyle = settings.widgetCompletedStyle,
                            onClick = { onOpenNote(note.id, note.isTask) },
                            onToggleCompleted = { viewModel.toggleCompleted(note) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    cell: CalendarCell,
    selected: Boolean,
    today: Boolean,
    entries: List<NoteEntity>,
    completedStyle: WidgetCompletedStyle,
    coloredTasks: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f)
    } else {
        Color.Transparent
    }
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        else -> Color.Transparent
    }
    val baseColor = when {
        !cell.inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val completedGreen = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF81C784)
    } else {
        Color(0xFF2E7D32)
    }
    val preview = remember(
        entries, completedStyle, coloredTasks, baseColor, completedGreen
    ) {
        val source = entries.map {
            WidgetDayEntry(it.title, it.isTask, it.isCompleted, it.taskColor)
        }
        val allLines = WidgetDayPreview.lines(source)
        val visible = allLines.take(2)
        val hidden = entries.size - visible.size
        val lines = if (hidden > 0) visible + WidgetPreviewLine("+$hidden", null) else visible
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("\n")
                val color = when {
                    line.isCompleted && completedStyle.usesGreen -> completedGreen
                    coloredTasks && line.colorKey != null -> taskColorOption(line.colorKey).accent
                    else -> baseColor.copy(alpha = 0.82f)
                }
                val decoration = if (line.isCompleted && completedStyle.usesStrike) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                }
                withStyle(SpanStyle(color = color, textDecoration = decoration)) {
                    append(line.text)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .padding(1.dp)
            .height(66.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(onClickLabel = uiText("Показать записи дня"), onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = cell.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = baseColor,
            textAlign = TextAlign.Center
        )
        if (entries.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 11.sp
                ),
                maxLines = 3,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CalendarNoteRow(
    note: NoteEntity,
    completedStyle: WidgetCompletedStyle,
    onClick: () -> Unit,
    onToggleCompleted: () -> Unit
) {
    val accent = taskColorOption(note.taskColor).accent
    val completedGreen = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF81C784)
    } else {
        Color(0xFF2E7D32)
    }
    val titleColor = when {
        note.isCompleted && completedStyle.usesGreen -> completedGreen
        note.isTask && !note.isCompleted -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    val decoration = if (note.isCompleted && completedStyle.usesStrike) {
        TextDecoration.LineThrough
    } else {
        TextDecoration.None
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (note.isTask) {
            Checkbox(
                checked = note.isCompleted,
                onCheckedChange = { onToggleCompleted() },
                colors = CheckboxDefaults.colors(
                    checkedColor = completedGreen,
                    uncheckedColor = accent
                )
            )
        } else {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.Notes,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.title.ifBlank { if (note.isTask) uiText("Задача") else uiText("Без названия") },
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                textDecoration = decoration,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (note.content.isNotBlank()) {
                Text(
                    text = remember(note.content, note.textFormat) { MarkdownLite.parse(note.content, formatting = note.textFormat) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun monthTitle(state: com.notessync.app.ui.calendar.CalendarState): String {
    val locale = com.notessync.app.i18n.UiStrings.locale
    val formatted = state.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
    return formatted.replaceFirstChar { it.titlecase(locale) }
}

private fun selectedDayTitle(date: LocalDate): String {
    val formatted = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", com.notessync.app.i18n.UiStrings.locale))
    return formatted.replaceFirstChar { it.titlecase(com.notessync.app.i18n.UiStrings.locale) }
}
