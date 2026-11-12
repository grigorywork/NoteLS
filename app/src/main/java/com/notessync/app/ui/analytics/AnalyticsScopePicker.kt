package com.notessync.app.ui.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.model.AnalyticsNotebook
import com.notessync.app.i18n.uiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalyticsScopePicker(scope: AnalyticsScope, notebooks: List<AnalyticsNotebook>, enabled: Boolean,
    onSelect: (AnalyticsScope) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val title = when (scope) {
        AnalyticsScope.All -> uiText("Все блокноты")
        AnalyticsScope.Unfiled -> uiText("Без блокнота")
        is AnalyticsScope.Notebook -> notebooks.firstOrNull { it.id == scope.id }?.name?.ifBlank { uiText("Без названия") } ?: uiText("Все блокноты")
    }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("analytics-filter")) {
        Icon(Icons.Default.FilterAlt, null); Spacer(Modifier.width(8.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        val index = when (scope) { AnalyticsScope.All -> 0; AnalyticsScope.Unfiled -> 1
            is AnalyticsScope.Notebook -> notebooks.indexOfFirst { it.id == scope.id }.takeIf { it >= 0 }?.plus(2) ?: 0 }
        val list = rememberLazyListState(index)
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(uiText("Блокнот для аналитики"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("analytics-notebooks")) {
                item(key = "all") { ScopeRow(uiText("Все блокноты"), scope == AnalyticsScope.All, "all") { onSelect(AnalyticsScope.All); open = false } }
                item(key = "unfiled") { ScopeRow(uiText("Без блокнота"), scope == AnalyticsScope.Unfiled, "unfiled") { onSelect(AnalyticsScope.Unfiled); open = false } }
                items(notebooks, key = { "book-${it.id}" }) { book ->
                    ScopeRow(book.name.ifBlank { uiText("Без названия") }, scope == AnalyticsScope.Notebook(book.id), "book-${book.id}") {
                        onSelect(AnalyticsScope.Notebook(book.id)); open = false
                    }
                }
            }
            TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) { Text(uiText("Закрыть")) }
        }
    }
}

@Composable
private fun ScopeRow(title: String, selected: Boolean, key: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("analytics-scope-$key").selectable(selected, onClick = onClick, role = Role.RadioButton)
        .padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp))
    }
}
