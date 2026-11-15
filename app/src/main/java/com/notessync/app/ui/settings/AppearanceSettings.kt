package com.notessync.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.*
import com.notessync.app.ui.theme.appFontFamily

@Composable
internal fun AppearanceSettings(state: SettingsState, settings: SettingsRepository, onQuote: () -> Unit, onLicenses: () -> Unit) {
    var showGlass by rememberSaveable { mutableStateOf(false) }
    if (showGlass) GlassSettingsDialog(state, { showGlass = false }, settings::setGlassAppearance)
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsDropdown("theme", uiText("Тема"), state.themeMode, ThemeMode.entries, settings::setThemeMode, { mode ->
            when (mode) { ThemeMode.SYSTEM -> uiText("Системная"); ThemeMode.LIGHT -> uiText("Светлая"); ThemeMode.DARK -> uiText("Тёмная") }
        })
        val scales = listOf(SettingsRepository.FONT_COMPACT, SettingsRepository.FONT_NORMAL, SettingsRepository.FONT_LARGE)
        SettingsDropdown("scale", uiText("Размер шрифта"), state.fontScale, scales, settings::setFontScale, { scale ->
            when (scale) { SettingsRepository.FONT_COMPACT -> uiText("Компактный"); SettingsRepository.FONT_LARGE -> uiText("Крупный"); else -> uiText("Обычный") }
        })
        SettingsDropdown("style", uiText("Стиль интерфейса"), state.interfaceStyle, InterfaceStyle.entries, settings::setInterfaceStyle, { it.label })
        if (state.interfaceStyle == InterfaceStyle.GLASS) {
            SettingsItem(headlineContent = { Text(uiText("Настроить стекло")) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.testTag("settings-glass-open").clickable { showGlass = true })
        }
        SettingsDropdown("accent", uiText("Цвет и обводка"), state.accentColor, AccentColor.entries, settings::setAccentColor, { it.label })
        SettingsDropdown("font", uiText("Шрифт"), state.appFont, AppFont.entries, settings::setAppFont, { it.label })
        // Render only the selected font; opening the menu does not load all font families.
        Text(uiText("Заметки и идеи — 0123456789"),
            fontFamily = appFontFamily(state.appFont), style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        SettingsHelp("fonts", uiText("О шрифтах")) {
            Text(uiText("Популярные шрифты") + " · " + uiText("Системные семейства"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onLicenses) { Text(uiText("Лицензии шрифтов")) }
        }
        SettingsDropdown("layout", uiText("Вид заметок"), state.notesLayout, NotesLayout.entries, settings::setNotesLayout, {
            if (it == NotesLayout.LIST) uiText("Список") else uiText("Две колонки")
        })
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsItem(headlineContent = { Text(uiText("Цитата в боковом меню")) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onQuote))
    }
}
