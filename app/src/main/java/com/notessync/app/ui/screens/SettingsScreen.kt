package com.notessync.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notessync.app.BuildConfig
import com.notessync.app.settings.AccentColor
import com.notessync.app.settings.AppFont
import com.notessync.app.settings.CompletedStyle
import com.notessync.app.settings.InterfaceStyle
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.ThemeMode
import com.notessync.app.settings.VoiceEngine
import com.notessync.app.settings.VoiceStructure
import com.notessync.app.ui.theme.accentPreviewColor
import com.notessync.app.ui.theme.appFontFamily

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSync: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val state by settings.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                .padding(horizontal = 16.dp)
        ) {
            SectionTitle("Внешний вид")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Тема", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { settings.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "Системная"
                                        ThemeMode.LIGHT -> "Светлая"
                                        ThemeMode.DARK -> "Тёмная"
                                    }
                                )
                            }
                        }
                    }
                    Text("Размер шрифта", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val scales = listOf(
                            SettingsRepository.FONT_COMPACT to "Компактный",
                            SettingsRepository.FONT_NORMAL to "Обычный",
                            SettingsRepository.FONT_LARGE to "Крупный"
                        )
                        scales.forEachIndexed { index, (scale, label) ->
                            SegmentedButton(
                                selected = state.fontScale == scale,
                                onClick = { settings.setFontScale(scale) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = scales.size)
                            ) { Text(label) }
                        }
                    }
                    // Этап 37: «Матовое стекло» — стиль Kimi на iPhone.
                    Text("Стиль интерфейса", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        InterfaceStyle.entries.forEachIndexed { index, style ->
                            SegmentedButton(
                                selected = state.interfaceStyle == style,
                                onClick = { settings.setInterfaceStyle(style) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = InterfaceStyle.entries.size)
                            ) { Text(style.label) }
                        }
                    }
                    Text(
                        "Матовое стекло — полупрозрачные панели с тонкой кромкой " +
                            "поверх мягкого градиента, как в Kimi на iPhone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text("Цвет и обводка", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        AccentColor.entries.forEach { accent ->
                            FilterChip(
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
                    // предпросмотр каждого — самим шрифтом. Ниже — системные семейства (Этап 34).
                    Text("Шрифт", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "Популярные шрифты",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        AppFont.entries.filter { it.isBundled }.forEach { font ->
                            FilterChip(
                                selected = state.appFont == font,
                                onClick = { settings.setAppFont(font) },
                                label = {
                                    Text(
                                        "${font.label} — Заметки",
                                        fontFamily = appFontFamily(font)
                                    )
                                }
                            )
                        }
                    }
                    Text(
                        "Системные семейства",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        AppFont.entries.filter { !it.isBundled }.forEach { font ->
                            FilterChip(
                                selected = state.appFont == font,
                                onClick = { settings.setAppFont(font) },
                                label = {
                                    Text(
                                        "${font.label} — Заметки",
                                        fontFamily = appFontFamily(font)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            SectionTitle("Редактор")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Выполненные пункты", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val styles = listOf(
                            CompletedStyle.STRIKE to "Зачёркнутые",
                            CompletedStyle.HIDE to "Скрывать"
                        )
                        styles.forEachIndexed { index, (style, label) ->
                            SegmentedButton(
                                selected = state.completedStyle == style,
                                onClick = { settings.setCompletedStyle(style) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size)
                            ) { Text(label) }
                        }
                    }
                    Text(
                        "В режиме правки выполненные всегда остаются видимыми — зелёными и зачёркнутыми.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            SectionTitle("Голосовой ввод")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Распознавание речи", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        VoiceEngine.entries.forEach { engine ->
                            FilterChip(
                                selected = state.voiceEngine == engine,
                                onClick = { settings.setVoiceEngine(engine) },
                                label = {
                                    Text(if (engine == VoiceEngine.ONLINE) "Онлайн" else "На устройстве")
                                }
                            )
                        }
                    }
                    Text(
                        state.voiceEngine.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text("Структуризация текста", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        VoiceStructure.entries.forEach { structure ->
                            FilterChip(
                                selected = state.voiceStructure == structure,
                                onClick = { settings.setVoiceStructure(structure) },
                                label = { Text(structure.label) }
                            )
                        }
                    }
                    Text(
                        "Кнопка микрофона с подписью AI — в верхней панели редактора. " +
                            "«По пунктам» превращает речь в список, «Основная мысль» выделяет главное.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            SectionTitle("Синхронизация")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Синхронизация по Wi-Fi") },
                    supportingContent = { Text("Связать устройства и обменяться заметками") },
                    leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSync)
                )
            }

            SectionTitle("О программе")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Версия") },
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
                ListItem(
                    headlineContent = { Text("Разработчик") },
                    supportingContent = { Text("MSS9LN") },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}
