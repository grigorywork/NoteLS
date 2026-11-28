package com.notessync.app.ui.screens

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.data.local.model.AnalyticsEntry
import com.notessync.app.ui.analytics.AnalyticsClock
import com.notessync.app.ui.analytics.AnalyticsPeriodsSection
import com.notessync.app.ui.analytics.AnalyticsTasksDialog
import com.notessync.app.ui.analytics.AnalyticsSummary
import com.notessync.app.ui.analytics.AnalyticsScopePicker
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import com.notessync.app.ui.analytics.AnalyticsState
import com.notessync.app.ui.analytics.AnalyticsViewModel
import com.notessync.app.ui.tasks.LocalTaskDurationStyle
import com.notessync.app.ui.tasks.taskColorOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Общие показатели записей и статистика задач с фильтром по блокнотам. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: AnalyticsViewModel = viewModel()
) {
    val computed by viewModel.state.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val request by viewModel.listRequest.collectAsStateWithLifecycle()
    val state = remember(computed, scope, notebooks, period, request) { AnalyticsSummary.visible(computed, scope, notebooks, period, request) }
    val choices = remember(notebooks) { notebooks.orEmpty().filter { !it.isDeleted && !it.isProtected && !it.isDiary } }
    AnalyticsClock(viewModel, state)
    request?.let { query -> AnalyticsTasksDialog(query, state.taskPage, viewModel::closeList,
        viewModel::selectPage) { row -> viewModel.openTask(row, onOpenNote) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiText("Аналитика")) },
                actions = { IconButton(onClick = viewModel::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, uiText("Обновить статистику")) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("Назад"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AnalyticsScopePicker(scope, choices, notebooks != null, viewModel::selectScope)
            Text(uiText("Статистика записей без пароля"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(uiText("Загрузка…"), modifier = Modifier.padding(vertical = 8.dp))
                return@Column
            }
            SummarySection(state)
            AnalyticsPeriodsSection(state, period, viewModel::selectPeriod, viewModel::showList)
            if (state.totalCompleted == 0) {
                Text(if (state.totalEntries == 0) uiText("В этом разделе пока нет записей.")
                    else uiText("Выполненных задач пока нет.\nОтметьте задачу выполненной — и здесь появится статистика."),
                    modifier = Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            StreakSection(state)
            SectionCard(title = uiText("Выполнения по дням (2 недели)")) {
                SimpleBarChart(
                    labels = state.byDay.map { dayLabel(it.first) },
                    values = state.byDay.map { it.second }
                )
            }
            SectionCard(title = uiText("Выполнения по неделям (8 недель)")) {
                SimpleBarChart(
                    labels = state.byWeek.map { weekLabel(it.first) },
                    values = state.byWeek.map { it.second }
                )
            }
            SectionCard(title = uiText("В какое время суток вы завершаете задачи")) {
                SimpleBarChart(
                    labels = (0 until 24).map { if (it % 6 == 0) "$it" else "" },
                    values = state.byHour.toList()
                )
            }
            if (state.byColor.isNotEmpty()) {
                SectionCard(title = uiText("По цветам задач")) {
                    ColorBreakdown(state.byColor)
                }
            }
            if (state.byNotebook.isNotEmpty()) {
                SectionCard(title = uiText("По блокнотам")) {
                    NotebookBreakdown(state.byNotebook)
                }
            }
            TimeSection(state)
            if (state.recentCompleted.isNotEmpty()) {
                SectionCard(title = uiText("Последние выполненные")) {
                    state.recentCompleted.forEach { note ->
                        CompletedRow(note = note, onClick = { viewModel.openRecent(note, onOpenNote) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- Разделы ----------

@Composable
private fun SummarySection(state: AnalyticsState) {
    SectionCard(title = uiText("Сводка")) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(uiText("Всего записей"), state.totalEntries.toString(), Modifier.weight(1f).testTag("stats-entries"))
            StatTile(uiText("Обычных заметок"), state.noteCount.toString(), Modifier.weight(1f).testTag("stats-notes"))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(uiText("Всего задач"), state.taskCount.toString(), Modifier.weight(1f).testTag("stats-tasks"))
            StatTile(uiText("Выполнено всего"), state.totalCompleted.toString(), Modifier.weight(1f).testTag("stats-completed"))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(uiText("Активных задач"), state.activeTasks.toString(), Modifier.weight(1f).testTag("stats-active"))
            StatTile(uiText("За 7 дней"), state.completedLast7Days.toString(), Modifier.weight(1f))
        }
        StatTile(uiText("За 30 дней"), state.completedLast30Days.toString())
    }
}

@Composable
private fun StreakSection(state: AnalyticsState) {
    SectionCard(title = uiText("Серии и рекорды")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    uiText("Текущая серия: %1\$s дн.", (state.currentStreak)),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    uiText("Рекордная серия: %1\$s дн.", (state.bestStreak)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.bestDay?.let { (day, count) ->
                    Text(
                        uiText("Лучший день: %1\$s — %2\$s задач", (fullDayLabel(day)), (count)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeSection(state: AnalyticsState) {
    val durationStyle = LocalTaskDurationStyle.current
    SectionCard(title = uiText("Время выполненных задач")) {
        Text(
            uiText("Всего: %1\$s", (durationStyle.format(state.totalElapsedMillis))),
            style = MaterialTheme.typography.titleMedium
        )
        if (state.averageElapsedMillis > 0L) {
            Text(
                uiText("Среднее на задачу: %1\$s", (durationStyle.format(state.averageElapsedMillis))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.fastestTitle?.let { title ->
            Text(
                uiText("Самая быстрая: %1\$s — %2\$s", (title.ifBlank { uiText("Без названия") }), (durationStyle.format(state.fastestMillis))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        state.slowestTitle?.let { title ->
            Text(
                uiText("Самая долгая: %1\$s — %2\$s", (title.ifBlank { uiText("Без названия") }), (durationStyle.format(state.slowestMillis))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompletedRow(note: AnalyticsEntry, onClick: () -> Unit) {
    val durationStyle = LocalTaskDurationStyle.current
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                note.title.ifBlank { uiText("Без названия") },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                fullDayLabel(
                    java.time.Instant.ofEpochMilli(note.taskCompletedAt ?: note.updatedAt)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                ) + if (note.taskElapsedMillis > 0L) {
                    " · " + durationStyle.format(note.taskElapsedMillis)
                } else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Диаграммы ----------

/** Столбчатая диаграмма без внешних библиотек: Canvas + подписи. */
@Composable
private fun SimpleBarChart(labels: List<String>, values: List<Int>) {
    if (values.isEmpty()) return
    val max = values.max().coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        // Значения над столбиками.
        Row(modifier = Modifier.fillMaxWidth()) {
            values.forEach { value ->
                Text(
                    if (value > 0) value.toString() else "",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val step = size.width / values.size
            val barWidth = step * 0.62f
            values.forEachIndexed { index, value ->
                val height = (value / max.toFloat()) * size.height
                val left = step * index + (step - barWidth) / 2f
                drawRoundRect(
                    color = if (value > 0) barColor else emptyColor,
                    topLeft = Offset(left, size.height - height.coerceAtLeast(3f)),
                    size = Size(barWidth, height.coerceAtLeast(3f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** Кольцевая диаграмма по цветам задач + легенда. */
@Composable
private fun ColorBreakdown(byColor: List<Pair<String, Int>>) {
    val total = byColor.sumOf { it.second }.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(140.dp)) {
                var startAngle = -90f
                byColor.forEach { (key, count) ->
                    val sweep = 360f * count / total
                    drawArc(
                        color = taskColorOption(key).completedContainer,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 30f, cap = StrokeCap.Butt)
                    )
                    startAngle += sweep
                }
            }
            Text(
                total.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            byColor.forEach { (key, count) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawCircle(taskColorOption(key).completedContainer)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${taskColorOption(key).label} — $count",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** Горизонтальные полосы по блокнотам. */
@Composable
private fun NotebookBreakdown(byNotebook: List<Pair<String?, Int>>) {
    val max = byNotebook.maxOf { it.second }.coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    byNotebook.take(8).forEach { (name, count) ->
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                "${name ?: uiText("Без блокнота")} — $count",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            ) {
                drawRoundRect(
                    color = barColor,
                    size = Size(size.width * count / max, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                )
            }
        }
    }
}

// ---------- Общие элементы ----------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 6.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

private fun dayLabel(day: LocalDate): String = day.dayOfMonth.toString()

private fun weekLabel(weekStart: LocalDate): String = weekStart.format(shortDateFormatter)

private fun fullDayLabel(day: LocalDate): String = day.format(shortDateFormatter)
