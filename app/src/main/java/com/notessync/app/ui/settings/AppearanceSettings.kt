package com.notessync.app.ui.settings

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.notessync.app.settings.AccentColor
import com.notessync.app.settings.AppFont
import com.notessync.app.settings.InterfaceStyle
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.ThemeMode
import com.notessync.app.ui.theme.accentPreviewColor
import com.notessync.app.ui.theme.appFontFamily
import com.notessync.app.settings.SettingsState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSettings(state: SettingsState, settings: SettingsRepository, onQuote: () -> Unit, onLicenses: () -> Unit) {
    var showGlass by rememberSaveable { mutableStateOf(false) }
    if (showGlass) GlassSettingsDialog(state, { showGlass = false }, settings::setGlassAppearance)
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(uiText("Тема"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    SettingsChoice(
                        selected = state.themeMode == mode,
                        onClick = { settings.setThemeMode(mode) }
                    ) {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> uiText("Системная")
                                ThemeMode.LIGHT -> uiText("Светлая")
                                ThemeMode.DARK -> uiText("Тёмная")
                            }
                        )
                    }
                }
            }
            Text(uiText("Размер шрифта"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                val scales = listOf(
                    SettingsRepository.FONT_COMPACT to uiText("Компактный"),
                    SettingsRepository.FONT_NORMAL to uiText("Обычный"),
                    SettingsRepository.FONT_LARGE to uiText("Крупный")
                )
                scales.forEach { (scale, label) ->
                    SettingsChoice(
                        selected = state.fontScale == scale,
                        onClick = { settings.setFontScale(scale) }
                    ) { Text(label) }
                }
            }
            // Этап 37: «Матовое стекло» — стиль Kimi на iPhone.
            Text(uiText("Стиль интерфейса"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                InterfaceStyle.entries.forEach { style ->
                    SettingsChoice(
                        selected = state.interfaceStyle == style,
                        onClick = { settings.setInterfaceStyle(style) }
                    ) { Text(style.label) }
                }
            }
            Text(
                uiText("Матовое стекло — полупрозрачные панели с тонкой кромкой ") +
                    uiText("поверх мягкого градиента, как в Kimi на iPhone."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (state.interfaceStyle == InterfaceStyle.GLASS) {
                TextButton(onClick = { showGlass = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-glass-open")) {
                    Text(uiText("Настроить стекло"))
                }
            }
            Text(uiText("Цвет и обводка"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)
            ) {
                AccentColor.entries.forEach { accent ->
                    SettingsChoice(
                        selected = state.accentColor == accent,
                        onClick = { settings.setAccentColor(accent) },
                        label = { Text(accent.label) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(accentPreviewColor(accent))
                            )
                        }
                    )
                }
            }
            // Этап 38: популярные шрифты (встроены в приложение, все с кириллицей),
            // One preview loads only the selected font, not all ten families.
            Text(uiText("Шрифт"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            Text(
                uiText("Популярные шрифты"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp)
            ) {
                AppFont.entries.filter { it.isBundled }.forEach { font ->
                    SettingsChoice(
                        selected = state.appFont == font,
                        onClick = { settings.setAppFont(font) },
                        label = {
                            Text(font.label)
                        }
                    )
                }
            }
            Text(
                uiText("Системные семейства"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp)
            ) {
                AppFont.entries.filter { !it.isBundled }.forEach { font ->
                    SettingsChoice(
                        selected = state.appFont == font,
                        onClick = { settings.setAppFont(font) },
                        label = {
                            Text(font.label)
                        }
                    )
                }
            }
            Text(
                uiText("Съешь ещё этих мягких французских булок, да выпей чаю.\nЗаметки, задачи и идеи — 0123456789"),
                fontFamily = appFontFamily(state.appFont),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            TextButton(onClick = { onLicenses() }) { Text(uiText("Лицензии шрифтов")) }
        }
    }

    LauncherIconSettings()
    PlannerShortcutSettings()

    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Флажки приоритета"), state.showTaskPriorities, settings::setShowTaskPriorities)
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsItem(headlineContent = { Text(uiText("Цитата в боковом меню")) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            modifier = Modifier.clickable { onQuote() })
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(uiText("Вид заметок"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
                com.notessync.app.settings.NotesLayout.entries.forEach { layout ->
                    SettingsChoice(state.notesLayout == layout, { settings.setNotesLayout(layout) }) {
                        Text(if (layout == com.notessync.app.settings.NotesLayout.LIST) uiText("Список") else uiText("Две колонки"))
                    }
                }
            }
        }
    }
}
