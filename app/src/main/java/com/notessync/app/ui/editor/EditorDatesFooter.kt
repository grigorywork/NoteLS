package com.notessync.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiStrings
import com.notessync.app.i18n.uiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun editorDateText(timestamp: Long?, locale: Locale, zone: ZoneId): String =
    timestamp?.takeIf { it > 0 }?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli(it))
    } ?: "—"

/** Absolute committed dates without a ticker; equal columns wrap at large font sizes. */
@Composable
internal fun EditorDatesFooter(createdAt: Long?, updatedAt: Long?, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val locale = UiStrings.locale
    val zone = remember(configuration) { ZoneId.systemDefault() }
    val created = remember(createdAt, locale, zone) { editorDateText(createdAt, locale, zone) }
    val updated = remember(updatedAt, locale, zone) { editorDateText(updatedAt, locale, zone) }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("editor-dates"),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DateColumn(uiText("Создано"), created, Modifier.weight(1f).testTag("editor-created"), false)
        DateColumn(uiText("Изменено"), updated, Modifier.weight(1f).testTag("editor-modified"), true)
    }
}

@Composable
private fun DateColumn(label: String, value: String, modifier: Modifier, right: Boolean) {
    Column(modifier, horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (right) TextAlign.End else TextAlign.Start)
    }
}
