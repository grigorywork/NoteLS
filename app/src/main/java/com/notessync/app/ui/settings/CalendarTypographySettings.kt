package com.notessync.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.*
import com.notessync.app.ui.theme.appFontFamily

@Composable
internal fun CalendarTypographySettings(state: SettingsState, settings: SettingsRepository) {
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Текст календаря"), style = MaterialTheme.typography.titleSmall)
            FontChoice("calendar", state.calendarFont?.label ?: uiText("Как в приложении"),
                listOf(null) + AppFont.entries, { it?.label ?: uiText("Как в приложении") }) {
                settings.setCalendarTypography(it, state.calendarTextSize)
            }
            TextSizeChoices("calendar", state.calendarTextSize) { settings.setCalendarTypography(state.calendarFont, it) }
            Text(uiText("Пн · 12 · Заметки и задачи"), maxLines = 2,
                fontFamily = appFontFamily(state.calendarFont ?: state.appFont),
                fontSize = (14 * state.fontScale * state.calendarTextSize.scale).sp,
                modifier = Modifier.testTag("calendar-text-preview"))
            TextButton(onClick = { settings.setCalendarTypography(null, CalendarTextSize.NORMAL) },
                modifier = Modifier.testTag("calendar-text-reset")) { Text(uiText("Сбросить текст календаря")) }
            HorizontalDivider()
            CalendarWidgetTypographySettings(state, settings)
        }
    }
}

@Composable
internal fun CalendarWidgetTypographySettings(state: SettingsState, settings: SettingsRepository) {
    val typography = state.calendarWidgetTypography
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(uiText("Текст календарных виджетов"), style = MaterialTheme.typography.titleSmall)
        FontChoice("calendar-widget", typography.font.label, CalendarWidgetFont.entries, { it.label }) {
            settings.setCalendarWidgetTypography(typography.copy(font = it))
        }
        TextSizeChoices("calendar-widget", typography.size) { settings.setCalendarWidgetTypography(typography.copy(size = it)) }
        Text(uiText("В виджетах доступны системные шрифты. Размер ограничен сеткой календаря."),
            style = MaterialTheme.typography.bodySmall)
        // Use the same system family names as the launcher; no bundled-font promise.
        Text(uiText("Пн · 12 · Заметки и задачи"), maxLines = 2,
            fontFamily = when (typography.font) {
                CalendarWidgetFont.DEFAULT, CalendarWidgetFont.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
                CalendarWidgetFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
                CalendarWidgetFont.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
                CalendarWidgetFont.CURSIVE -> androidx.compose.ui.text.font.FontFamily.Cursive
            }, fontSize = (14 * typography.size.scale).sp,
            modifier = Modifier.testTag("calendar-widget-text-preview"))
        TextButton(onClick = { settings.setCalendarWidgetTypography(CalendarWidgetTypography()) },
            modifier = Modifier.testTag("calendar-widget-text-reset")) { Text(uiText("Сбросить текст виджетов")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSizeChoices(tag: String, value: CalendarTextSize, onChange: (CalendarTextSize) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
        CalendarTextSize.entries.forEach { size ->
            FilterChip(selected = value == size, onClick = { onChange(size) }, label = { Text(size.label) },
                modifier = Modifier.testTag("$tag-size-${size.name}"))
        }
    }
}

@Composable
private fun <T> FontChoice(tag: String, selected: String, choices: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    SettingsDropdown("$tag-font", uiText("Шрифт"), selected, choices.map(label),
        onSelect = { selectedLabel ->
            // Nullable calendar font is a real option (inherit), not a missing menu entry.
            val index = choices.indexOfFirst { label(it) == selectedLabel }
            if (index >= 0) onChange(choices[index])
        }, label = { it })
}
