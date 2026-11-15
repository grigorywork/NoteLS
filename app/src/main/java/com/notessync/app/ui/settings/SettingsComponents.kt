package com.notessync.app.ui.settings

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}


/** One visible contour per group, with space between neighbouring groups in either interface style. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = modifier.padding(bottom = 8.dp), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        content = content)
}

@Composable
internal fun SettingsChoice(selected: Boolean, onClick: () -> Unit, enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null, label: @Composable () -> Unit
) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = label, leadingIcon = leadingIcon,
        modifier = Modifier.semantics { role = Role.RadioButton },
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected,
            borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp, selectedBorderWidth = 2.dp))
}

@Composable
internal fun SettingsItem(headlineContent: @Composable () -> Unit, modifier: Modifier = Modifier,
    supportingContent: @Composable (() -> Unit)? = null, leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null, divider: Boolean = false
) {
    androidx.compose.material3.ListItem(headlineContent = headlineContent, modifier = modifier,
        supportingContent = supportingContent, leadingContent = leadingContent, trailingContent = trailingContent,
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent))
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}


/** Compose only the open group; expensive credential controls are disposed when it closes. */
@Composable
internal fun SettingsGroup(id: String, title: String, icon: ImageVector, expanded: Boolean, enabled: Boolean,
    onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val description = uiText(if (expanded) "Развёрнуто" else "Свёрнуто")
    SettingsCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-group-$id")
            .semantics(mergeDescendants = true) { heading(); stateDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp))
        }
    }
    if (expanded) Column(Modifier.fillMaxWidth().testTag("settings-content-$id"), content = content)
}

@Composable
internal fun SettingsToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, padded: Boolean = true) {
    Row(modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
        .toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = if (padded) 12.dp else 0.dp, vertical = if (padded) 4.dp else 0.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        androidx.compose.material3.Switch(checked, onCheckedChange = null)
    }
}
