package com.notessync.app.ui.screens

import com.notessync.app.i18n.uiText

import com.notessync.app.i18n.UiLanguage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.settings.AccentColor
import com.notessync.app.settings.AppFont
import com.notessync.app.settings.BackupFrequency
import com.notessync.app.settings.CompletedStyle
import com.notessync.app.settings.InterfaceStyle
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.ThemeMode
import com.notessync.app.settings.VoiceControl
import com.notessync.app.settings.VoiceEngine
import com.notessync.app.settings.VoiceStructure
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.theme.accentPreviewColor
import com.notessync.app.ui.theme.appFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSync: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val state by settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Этап 39: состояние и лончеры резервного копирования.
    var pendingRestore by remember { mutableStateOf<BackupManager.BackupInfo?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var localCopies by remember { mutableStateOf<List<BackupManager.LocalBackup>>(emptyList()) }
    var showLocalCopies by remember { mutableStateOf(false) }
    var exportCopy by remember { mutableStateOf<java.io.File?>(null) }
    var showDrawerQuote by remember { mutableStateOf(false) }
    var showFontLicenses by remember { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Finish the database operation before navigating away. */ }
    if (showDrawerQuote) com.notessync.app.ui.quotes.DrawerQuoteDialog(state.drawerQuote,
        onSave = { settings.setDrawerQuoteAppearance(it); showDrawerQuote = false }, onDismiss = { showDrawerQuote = false })
    if (showFontLicenses) FontLicensesDialog(onDismiss = { showFontLicenses = false })

    LaunchedEffect(state.lastAutoBackupAt, showLocalCopies) {
        localCopies = BackupManager.localBackups(context)
    }

    val exportCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val file = exportCopy
        exportCopy = null
        if (uri != null && file != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error(uiText("Не удалось открыть файл"))
                    output.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                snackbarHostState.showSnackbar(uiText("Копия сохранена в выбранную папку"))
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { snackbarHostState.showSnackbar(uiText("Не удалось сохранить копию")) }
            finally { busy = false }
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            BackupManager.createBackup(context, out)
                        } != null
                    }
                }.getOrDefault(false)
                busy = false
                if (ok) {
                    settings.setLastBackupAt(System.currentTimeMillis())
                    snackbarHostState.showSnackbar(uiText("Бэкап сохранён"))
                } else {
                    snackbarHostState.showSnackbar(uiText("Не удалось создать бэкап"))
                }
            }
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val info = BackupManager.inspect(context, uri)
                busy = false
                if (info != null) {
                    pendingRestoreUri = uri
                    pendingRestore = info
                } else {
                    snackbarHostState.showSnackbar(uiText("Это не бэкап NoteLS"))
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiText("Настройки")) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("Назад"))
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
            SectionTitle(uiText("Язык интерфейса"))
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                    UiLanguage.entries.forEach { language ->
                        SettingsChoice(selected = state.uiLanguage == language, onClick = { settings.setUiLanguage(language) }) {
                            Text(language.nativeName)
                        }
                    }
                }
            }
            SectionTitle(uiText("Внешний вид"))
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
                    TextButton(onClick = { showFontLicenses = true }) { Text(uiText("Лицензии шрифтов")) }
                }
            }

            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Флажки приоритета"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.showTaskPriorities, settings::setShowTaskPriorities)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                SettingsItem(headlineContent = { Text(uiText("Цитата в боковом меню")) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { showDrawerQuote = true })
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
            SectionTitle(uiText("Редактор"))
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Сразу выбирать время быстрой задачи"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.quickTaskTime, settings::setQuickTaskTime)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Камера в редакторе"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.cameraCapture, settings::setCameraCapture)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Размеры фото"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.photoResize, settings::setPhotoResize)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Показывать таблицы"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.showTables, settings::setShowTables)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("История изменений"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.editorHistory, settings::setEditorHistory)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(uiText("Расширенное оформление"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.advancedFormatting, settings::setAdvancedFormatting)
                }
            }
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(uiText("Выполненные пункты"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                        val styles = listOf(
                            CompletedStyle.STRIKE to uiText("Зачёркнутые"),
                            CompletedStyle.HIDE to uiText("Скрывать")
                        )
                        styles.forEach { (style, label) ->
                            SettingsChoice(
                                selected = state.completedStyle == style,
                                onClick = { settings.setCompletedStyle(style) }
                            ) { Text(label) }
                        }
                    }
                    Text(
                        uiText("В режиме правки выполненные всегда остаются видимыми — зелёными и зачёркнутыми."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            SectionTitle(uiText("Голосовой ввод"))
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(uiText("Распознавание речи"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)
                    ) {
                        VoiceEngine.entries.forEach { engine ->
                            SettingsChoice(
                                selected = state.voiceEngine == engine,
                                onClick = { settings.setVoiceEngine(engine) },
                                label = {
                                    Text(if (engine == VoiceEngine.ONLINE) uiText("Онлайн") else uiText("На устройстве"))
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
                    Text(uiText("Онлайн-режим использует системный сервис: звук может передаваться его провайдеру. Локальный режим требует Android 12+ и доступную модель на устройстве; автоматического перехода в онлайн нет."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(uiText("Управление микрофоном"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)) {
                        VoiceControl.entries.forEach { control ->
                            SettingsChoice(selected = state.voiceControl == control, onClick = { settings.setVoiceControl(control) },
                                label = { Text(control.label) })
                        }
                    }
                    Text(uiText("Структуризация текста"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)
                    ) {
                        VoiceStructure.entries.forEach { structure ->
                            SettingsChoice(
                                selected = state.voiceStructure == structure,
                                onClick = { settings.setVoiceStructure(structure) },
                                label = { Text(structure.label) }
                            )
                        }
                    }
                    Text(
                        uiText("Удерживайте микрофон и говорите; отпустите, чтобы получить текст. Режим нажатий и обычная клавиатура доступны, если удерживать неудобно. Структуризация — локальные правила, без отдельного ИИ-сервиса."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            SectionTitle(uiText("Аудиовложения"))
            SettingsCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(uiText("Запись аудио в заметку"), Modifier.weight(1f))
                        androidx.compose.material3.Switch(state.audioRecording, settings::setAudioRecording)
                    }
                    Text(uiText("До 20 минут и 10 МиБ на файл. Выключение скрывает запись; готовые аудиовложения остаются доступны."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }

            SectionTitle(uiText("Синхронизация"))
            SettingsCard(Modifier.fillMaxWidth()) {
                com.notessync.app.ai.TransformSettingsSection(state, settings)
            }
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    headlineContent = { Text(uiText("Синхронизация по Wi-Fi")) },
                    supportingContent = { Text(uiText("Связать устройства и обменяться заметками")) },
                    leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSync)
                )
            }

            // Этап 39: резервное копирование — ручной бэкап/восстановление
            // и отдельная настройка частоты автобэкапа.
            SectionTitle(uiText("Резервное копирование"))
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    headlineContent = { Text(uiText("Сохранить резервную копию")) },
                    divider = true,
                    supportingContent = { Text(uiText("ZIP-архив: заметки, задачи, блокноты и фото")) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { createBackupLauncher.launch(BackupManager.suggestedFileName()) }
                )
                SettingsItem(
                    headlineContent = { Text(uiText("Восстановить из файла")) },
                    divider = true,
                    supportingContent = { Text(uiText("Заменить текущие данные содержимым архива")) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) {
                            openBackupLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream")
                            )
                        }
                )
                SettingsItem(
                    headlineContent = { Text(uiText("Копии на этом устройстве")) },
                    divider = true,
                    supportingContent = { Text(uiText("Доступно: %1\$s. Восстановить или сохранить в папку", (localCopies.size))) },
                    leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { showLocalCopies = true }
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(uiText("Автоматические копии"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)
                    ) {
                        BackupFrequency.entries.forEach { frequency ->
                            SettingsChoice(
                                selected = state.backupFrequency == frequency,
                                onClick = { settings.setBackupFrequency(frequency) },
                                enabled = !busy,
                                label = { Text(frequency.label) }
                            )
                        }
                    }
                    Text(
                        if (state.lastBackupAt > 0) {
                            uiText("Последний бэкап: %1\$s", (formatDateTime(state.lastBackupAt)))
                        } else {
                            uiText("Бэкап ещё не создавался")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        uiText("Хранятся 5 последних копий на устройстве. Проверка — при открытии и закрытии приложения, ") +
                            uiText("без постоянно работающей службы. «После каждой заметки» — при выходе из редактора. ") +
                            uiText("При удалении приложения локальные копии тоже удаляются: важную копию сохраните в выбранную папку."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            SectionTitle(uiText("О программе"))
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    headlineContent = { Text(uiText("Версия")) },
                    divider = true,
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
                SettingsItem(
                    headlineContent = { Text(uiText("Разработчик")) },
                    supportingContent = { Text("MSS9LN") },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) }
                )
            }
        }
    }

    if (showLocalCopies) {
        AlertDialog(
            onDismissRequest = { showLocalCopies = false },
            title = { Text(uiText("Копии на устройстве")) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (localCopies.isEmpty()) Text(uiText("Автоматических копий пока нет"))
                    localCopies.forEach { copy ->
                        Text(formatDateTime(copy.info.createdAt), style = MaterialTheme.typography.titleSmall)
                        Text(uiText("Записей: %1\$s", (copy.info.noteCount)), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.foundation.layout.Row {
                            TextButton(onClick = {
                                showLocalCopies = false
                                pendingRestoreUri = android.net.Uri.fromFile(copy.file)
                                pendingRestore = copy.info
                            }) { Text(uiText("Восстановить")) }
                            TextButton(onClick = {
                                showLocalCopies = false
                                exportCopy = copy.file
                                exportCopyLauncher.launch(BackupManager.suggestedFileName())
                            }) { Text(uiText("Сохранить в папку")) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLocalCopies = false }) { Text(uiText("Закрыть")) } }
        )
    }

    // Текущие данные заменяются только после проверки всей копии.
    val restoreInfo = pendingRestore
    val restoreUri = pendingRestoreUri
    if (restoreInfo != null && restoreUri != null) {
        AlertDialog(
            onDismissRequest = {
                pendingRestore = null
                pendingRestoreUri = null
            },
            title = { Text(uiText("Восстановить резервную копию?")) },
            text = {
                Text(
                    buildString {
                        if (restoreInfo.createdAt > 0) {
                            append(uiText("Бэкап от %1\$s", (formatDateTime(restoreInfo.createdAt))))
                        } else {
                            append(uiText("Бэкап NoteLS"))
                        }
                        if (restoreInfo.noteCount >= 0) append(uiText(", заметок: %1\$s", (restoreInfo.noteCount)))
                        append(uiText(".\n\nТекущие заметки, задачи и фото будут полностью заменены содержимым архива."))
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    pendingRestoreUri = null
                    scope.launch {
                        busy = true
                        val result = BackupManager.restore(context, restoreUri)
                        busy = false
                        if (result.isSuccess) {
                            snackbarHostState.showSnackbar(uiText("Данные восстановлены. Автообмен приостановлен."))
                        } else {
                            snackbarHostState.showSnackbar(
                                uiText("Не удалось восстановить: %1\$s", (result.exceptionOrNull()?.message ?: uiText("ошибка")))
                            )
                        }
                    }
                }) { Text(uiText("Восстановить")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    pendingRestoreUri = null
                }) { Text(uiText("Отмена")) }
            }
        )
    }

}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}


/** One visible contour per group, with space between neighbouring groups in either interface style. */
@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = modifier.padding(bottom = 12.dp), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        content = content)
}

@Composable
private fun SettingsChoice(selected: Boolean, onClick: () -> Unit, enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null, label: @Composable () -> Unit
) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = label, leadingIcon = leadingIcon,
        modifier = Modifier.semantics { role = Role.RadioButton },
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected,
            borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp, selectedBorderWidth = 2.dp))
}

@Composable
private fun SettingsItem(headlineContent: @Composable () -> Unit, modifier: Modifier = Modifier,
    supportingContent: @Composable (() -> Unit)? = null, leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null, divider: Boolean = false
) {
    androidx.compose.material3.ListItem(headlineContent = headlineContent, modifier = modifier,
        supportingContent = supportingContent, leadingContent = leadingContent, trailingContent = trailingContent,
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent))
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
