package com.notessync.app.ui.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notessync.app.i18n.uiText
import com.notessync.app.ui.common.formatDateTime

@Composable
internal fun AnalyticsPeriodsSection(state: AnalyticsState, period: AnalyticsPeriod,
    onPeriod: (AnalyticsPeriod) -> Unit, onList: (AnalyticsTaskList) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Сравнение периодов"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsPeriod.entries.forEach { item ->
                    FilterChip(selected = item == period, onClick = { onPeriod(item) },
                        label = { Text(uiText("%1\$s дней", item.days)) },
                        modifier = Modifier.weight(1f).testTag("analytics-period-${item.name.lowercase()}"))
                }
            }
            Text(uiText("Последние %1\$s дней, включая сегодня", period.days), style = MaterialTheme.typography.bodySmall)
            ComparisonRow(uiText("Текущий период"), state.comparison.current, "current") { onList(AnalyticsTaskList.CURRENT) }
            ComparisonRow(uiText("Предыдущий период"), state.comparison.previous, "previous") { onList(AnalyticsTaskList.PREVIOUS) }
            val delta = state.comparison.difference.let { if (it > 0) "+$it" else it.toString() }
            Text(uiText("Разница: %1\$s", delta), modifier = Modifier.testTag("analytics-difference"),
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onList(AnalyticsTaskList.ACTIVE) }, modifier = Modifier.weight(1f).testTag("analytics-active")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiText("Активные задачи"), maxLines = 2, textAlign = TextAlign.Center)
                        Text(state.activeTasks.toString())
                    }
                }
                OutlinedButton(onClick = { onList(AnalyticsTaskList.OVERDUE) }, modifier = Modifier.weight(1f).testTag("analytics-overdue")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiText("Просроченные задачи"), maxLines = 2, textAlign = TextAlign.Center)
                        Text(state.overdueTasks.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonRow(title: String, count: Int, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("analytics-$tag")
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun AnalyticsTasksDialog(request: AnalyticsListRequest, page: AnalyticsTaskPage?, onDismiss: () -> Unit,
    onPage: (Int) -> Unit, onOpen: (AnalyticsTaskRow) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp).heightIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiText(when (request.kind) {
                    AnalyticsTaskList.CURRENT -> "Текущий период"
                    AnalyticsTaskList.PREVIOUS -> "Предыдущий период"
                    AnalyticsTaskList.ACTIVE -> "Активные задачи"
                    AnalyticsTaskList.OVERDUE -> "Просроченные задачи"
                }), style = MaterialTheme.typography.titleLarge)
                if (page == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(uiText("Загрузка…"))
                } else {
                    Text(uiText("Всего задач: %1\$s", page.total), modifier = Modifier.testTag("analytics-list-total"))
                    if (page.total == 0) Text(uiText("Подходящих задач нет."), Modifier.padding(vertical = 12.dp))
                    else key(page.request) { LazyColumn(Modifier.fillMaxWidth().weight(1f, false).heightIn(max = 320.dp).testTag("analytics-task-list")) {
                        items(page.rows, key = { "${it.id}:${it.syncId}" }) { row ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("analytics-task-${row.id}")
                                .clickable(role = Role.Button) { onOpen(row) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Text(row.title.ifBlank { uiText("Без названия") }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge)
                                (row.completedAt ?: row.dueAt)?.let { at ->
                                    Text(formatDateTime(at), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPage(page.request.page - 1) }, enabled = page.request.page > 0) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Предыдущая страница"))
                        }
                        Text(uiText("Страница %1\$s из %2\$s", page.request.page + 1, page.pageCount),
                            Modifier.weight(1f).testTag("analytics-page"), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { onPage(page.request.page + 1) }, enabled = page.request.page + 1 < page.pageCount) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, uiText("Следующая страница"))
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(uiText("Закрыть")) }
            }
        }
    }
}
