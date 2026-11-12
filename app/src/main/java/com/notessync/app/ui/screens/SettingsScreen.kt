package com.notessync.app.ui.screens

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
                        ?: error("Не удалось открыть файл")
                    output.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                snackbarHostState.showSnackbar("Копия сохранена в выбранную папку")
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { snackbarHostState.showSnackbar("Не удалось сохранить копию") }
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
                    snackbarHostState.showSnackbar("Бэкап сохранён")
                } else {
                    snackbarHostState.showSnackbar("Не удалось создать бэкап")
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
                    snackbarHostState.showSnackbar("Это не бэкап NoteLS")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
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
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Тема", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                        ThemeMode.entries.forEach { mode ->
                            SettingsChoice(
                                selected = state.themeMode == mode,
                                onClick = { settings.setThemeMode(mode) }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                        val scales = listOf(
                            SettingsRepository.FONT_COMPACT to "Компактный",
                            SettingsRepository.FONT_NORMAL to "Обычный",
                            SettingsRepository.FONT_LARGE to "Крупный"
                        )
                        scales.forEach { (scale, label) ->
                            SettingsChoice(
                                selected = state.fontScale == scale,
                                onClick = { settings.setFontScale(scale) }
                            ) { Text(label) }
                        }
                    }
                    // Этап 37: «Матовое стекло» — стиль Kimi на iPhone.
                    Text("Стиль интерфейса", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
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
                        "Матовое стекло — полупрозрачные панели с тонкой кромкой " +
                            "поверх мягкого градиента, как в Kimi на iPhone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text("Цвет и обводка", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
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
                    Text("Шрифт", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "Популярные шрифты",
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
                        "Системные семейства",
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
                        "Съешь ещё этих мягких французских булок, да выпей чаю.\nЗаметки, задачи и идеи — 0123456789",
                        fontFamily = appFontFamily(state.appFont),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    TextButton(onClick = { showFontLicenses = true }) { Text("Лицензии шрифтов") }
                }
            }

            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Флажки приоритета", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.showTaskPriorities, settings::setShowTaskPriorities)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                SettingsItem(headlineContent = { Text("Цитата в боковом меню") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { showDrawerQuote = true })
            }
            SectionTitle("Редактор")
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Сразу выбирать время быстрой задачи", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.quickTaskTime, settings::setQuickTaskTime)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Камера в редакторе", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.cameraCapture, settings::setCameraCapture)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Размеры фото", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.photoResize, settings::setPhotoResize)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Показывать таблицы", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.showTables, settings::setShowTables)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("История изменений", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.editorHistory, settings::setEditorHistory)
                }
            }
            SettingsCard(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Расширенное оформление", Modifier.weight(1f))
                    androidx.compose.material3.Switch(state.advancedFormatting, settings::setAdvancedFormatting)
                }
            }
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Выполненные пункты", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
                        val styles = listOf(
                            CompletedStyle.STRIKE to "Зачёркнутые",
                            CompletedStyle.HIDE to "Скрывать"
                        )
                        styles.forEach { (style, label) ->
                            SettingsChoice(
                                selected = state.completedStyle == style,
                                onClick = { settings.setCompletedStyle(style) }
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
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Распознавание речи", style = MaterialTheme.typography.titleSmall)
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
                        "Кнопка микрофона с подписью AI — в верхней панели редактора. " +
                            "«По пунктам» превращает речь в список, «Основная мысль» выделяет главное.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            SectionTitle("Синхронизация")
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    headlineContent = { Text("Синхронизация по Wi-Fi") },
                    supportingContent = { Text("Связать устройства и обменяться заметками") },
                    leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSync)
                )
            }

            // Этап 39: резервное копирование — ручной бэкап/восстановление
            // и отдельная настройка частоты автобэкапа.
            SectionTitle("Резервное копирование")
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    headlineContent = { Text("Сохранить резервную копию") },
                    divider = true,
                    supportingContent = { Text("ZIP-архив: заметки, задачи, блокноты и фото") },
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
                    headlineContent = { Text("Восстановить из файла") },
                    divider = true,
                    supportingContent = { Text("Заменить текущие данные содержимым архива") },
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
                    headlineContent = { Text("Копии на этом устройстве") },
                    divider = true,
                    supportingContent = { Text("Доступно: ${localCopies.size}. Восстановить или сохранить в папку") },
                    leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { showLocalCopies = true }
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Автоматические копии", style = MaterialTheme.typography.titleSmall)
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
                            "Последний бэкап: ${formatDateTime(state.lastBackupAt)}"
                        } else {
                            "Бэкап ещё не создавался"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "Хранятся 5 последних копий на устройстве. Проверка — при открытии и закрытии приложения, " +
                            "без постоянно работающей службы. «После каждой заметки» — при выходе из редактора. " +
                            "При удалении приложения локальные копии тоже удаляются: важную копию сохраните в выбранную папку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            SectionTitle("О программе")
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsItem(
                    headlineContent = { Text("Версия") },
                    divider = true,
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
                SettingsItem(
                    headlineContent = { Text("Разработчик") },
                    supportingContent = { Text("MSS9LN") },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) }
                )
            }
        }
    }

    if (showLocalCopies) {
        AlertDialog(
            onDismissRequest = { showLocalCopies = false },
            title = { Text("Копии на устройстве") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (localCopies.isEmpty()) Text("Автоматических копий пока нет")
                    localCopies.forEach { copy ->
                        Text(formatDateTime(copy.info.createdAt), style = MaterialTheme.typography.titleSmall)
                        Text("Записей: ${copy.info.noteCount}", style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.foundation.layout.Row {
                            TextButton(onClick = {
                                showLocalCopies = false
                                pendingRestoreUri = android.net.Uri.fromFile(copy.file)
                                pendingRestore = copy.info
                            }) { Text("Восстановить") }
                            TextButton(onClick = {
                                showLocalCopies = false
                                exportCopy = copy.file
                                exportCopyLauncher.launch(BackupManager.suggestedFileName())
                            }) { Text("Сохранить в папку") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLocalCopies = false }) { Text("Закрыть") } }
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
            title = { Text("Восстановить резервную копию?") },
            text = {
                Text(
                    buildString {
                        if (restoreInfo.createdAt > 0) {
                            append("Бэкап от ${formatDateTime(restoreInfo.createdAt)}")
                        } else {
                            append("Бэкап NoteLS")
                        }
                        if (restoreInfo.noteCount >= 0) append(", заметок: ${restoreInfo.noteCount}")
                        append(".\n\nТекущие заметки, задачи и фото будут полностью заменены содержимым архива.")
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
                            snackbarHostState.showSnackbar("Данные восстановлены")
                        } else {
                            snackbarHostState.showSnackbar(
                                "Не удалось восстановить: ${result.exceptionOrNull()?.message ?: "ошибка"}"
                            )
                        }
                    }
                }) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    pendingRestoreUri = null
                }) { Text("Отмена") }
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
