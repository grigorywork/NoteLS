package com.notessync.app.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.i18n.uiText

@Composable
internal fun TaskFiltersBar(value: TaskFilters, overdue: Boolean, notebooks: List<NotebookEntity>, tags: List<TagEntity>, onChange: (TaskFilters) -> Unit) {
    var picker by remember { mutableIntStateOf(0) }
    Row(Modifier.testTag("task-filters").fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Этап 80: один тап показывает только задачи с идущим секундомером или отсчётом.
        FilterChip(selected = value.running, onClick = { onChange(value.copy(running = !value.running)) },
            label = { Text(uiText("Запущенные")) })
        AssistChip(onClick = { picker = 1 }, label = { Text(if (overdue) TaskDueFilter.OVERDUE.label else value.due.label) })
        AssistChip(onClick = { picker = 2 }, label = { Text(value.priority?.let { TaskPriority.from(it).label } ?: uiText("Все приоритеты")) })
        AssistChip(onClick = { picker = 3 }, label = { Text(when (value.notebookId) {
            null -> uiText("Все блокноты"); 0L -> uiText("Без блокнота"); else -> notebooks.find { !it.isDiary && it.id == value.notebookId }?.name?.take(80) ?: uiText("Блокнот")
        }, maxLines = 1) })
        AssistChip(onClick = { picker = 4 }, label = { Text(value.tagId?.let { id -> tags.find { it.id == id }?.name?.take(80)?.let { "#$it" } } ?: uiText("Все теги"), maxLines = 1) })
        if (value != TaskFilters() || overdue) TextButton(onClick = { onChange(TaskFilters()) }) { Text(uiText("Сбросить фильтры")) }
    }
    fun close() { picker = 0 }
    when (picker) {
        1 -> FilterPicker(uiText("Срок задачи"), TaskDueFilter.entries.map { it to it.label }, { close() }) { onChange(value.copy(due = it)); close() }
        2 -> FilterPicker(uiText("Приоритет"), listOf(null to uiText("Все приоритеты")) + TaskPriority.entries.map { it.value to it.label }, { close() }) { onChange(value.copy(priority = it)); close() }
        3 -> FilterPicker(uiText("Блокнот"), listOf(null to uiText("Все блокноты"), 0L to uiText("Без блокнота")) + notebooks.filterNot { it.isDiary }.map { it.id to it.name }, { close() }) { onChange(value.copy(notebookId = it)); close() }
        4 -> FilterPicker(uiText("Теги"), listOf(null to uiText("Все теги")) + tags.map { it.id to "#${it.name}" }, { close() }) { onChange(value.copy(tagId = it)); close() }
    }
}

@Composable
private fun <T> FilterPicker(title: String, options: List<Pair<T, String>>, onDismiss: () -> Unit, onChoose: (T) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(options) { (value, label) -> TextButton(onClick = { onChoose(value) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(label.take(256), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(uiText("Закрыть")) } })
}
