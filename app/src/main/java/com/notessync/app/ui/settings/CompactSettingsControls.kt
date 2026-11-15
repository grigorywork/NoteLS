package com.notessync.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText

/** One compact row; the complete names and selected state remain available in the menu. */
@Composable
internal fun <T> SettingsDropdown(id: String, title: String, value: T, options: List<T>,
    onSelect: (T) -> Unit, label: (T) -> String, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-choice-$id")
            .semantics(mergeDescendants = true) { stateDescription = label(value) }
            .clickable(enabled = enabled, role = Role.Button) { expanded = true }
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(label(value), Modifier.widthIn(max = 132.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded && enabled, { expanded = false }, Modifier.heightIn(max = 320.dp).widthIn(min = 240.dp, max = 320.dp)) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) },
                    leadingIcon = { RadioButton(option == value, onClick = null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.semantics { selected = option == value },
                    onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

@Composable
internal fun SettingsHelp(id: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val description = uiText(if (expanded) "Развёрнуто" else "Свёрнуто")
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("settings-help-$id")
        .semantics { stateDescription = description }) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp))
    }
    if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
}
