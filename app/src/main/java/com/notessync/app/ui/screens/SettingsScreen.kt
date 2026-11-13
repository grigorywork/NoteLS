package com.notessync.app.ui.screens

import com.notessync.app.ui.settings.*
import com.notessync.app.i18n.uiText
import com.notessync.app.i18n.UiLanguage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notessync.app.BuildConfig
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.common.formatDateTime
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
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var localCopies by remember { mutableStateOf<List<BackupManager.LocalBackup>>(emptyList()) }
    var showLocalCopies by remember { mutableStateOf(false) }
    var exportCopy by remember { mutableStateOf<java.io.File?>(null) }
    var showDrawerQuote by remember { mutableStateOf(false) }
    var showFontLicenses by remember { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Finish the database operation before navigating away. */ }
    BackHandler(enabled = !busy && expandedGroup != null) { expandedGroup = null }
    if (showDrawerQuote) com.notessync.app.ui.quotes.DrawerQuoteDialog(state.drawerQuote,
        onSave = { settings.setDrawerQuoteAppearance(it); showDrawerQuote = false }, onDismiss = { showDrawerQuote = false })
    if (showFontLicenses) FontLicensesDialog(onDismiss = { showFontLicenses = false })

    LaunchedEffect(state.lastAutoBackupAt, showLocalCopies, expandedGroup) {
        if (expandedGroup == "backup" || showLocalCopies) localCopies = BackupManager.localBackups(context)
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
                    IconButton(onClick = { if (expandedGroup != null) expandedGroup = null else onBack() }, enabled = !busy) {
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
            SettingsGroup("appearance", uiText("Внешний вид"), Icons.Default.Palette, expandedGroup == "appearance", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "appearance") null else "appearance" }) {
                AppearanceSettings(state, settings, { showDrawerQuote = true }, { showFontLicenses = true })
            }
            SettingsGroup("editor", uiText("Редактор"), Icons.Default.Edit, expandedGroup == "editor", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "editor") null else "editor" }) {
                EditorSettings(state, settings)
            }
            SettingsGroup("voice", uiText("Голос и аудио"), Icons.Default.Mic, expandedGroup == "voice", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "voice") null else "voice" }) {
                VoiceSettings(state, settings)
            }
            SettingsGroup("transform", uiText("Преобразователь текста"), Icons.Default.AutoFixHigh, expandedGroup == "transform", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "transform") null else "transform" }) {
                SettingsCard(Modifier.fillMaxWidth()) {
                    com.notessync.app.ai.TransformSettingsSection(state, settings)
                }
            }
            SettingsGroup("sync", uiText("Синхронизация"), Icons.Default.Sync, expandedGroup == "sync", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "sync") null else "sync" }) {
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

            }
            SettingsGroup("backup", uiText("Резервное копирование"), Icons.Default.Backup, expandedGroup == "backup", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "backup") null else "backup" }) {
                BackupSettings(state, settings, busy, localCopies.size, { createBackupLauncher.launch(it) }, { openBackupLauncher.launch(it) }, { showLocalCopies = true })
            }
            SettingsGroup("about", uiText("О программе"), Icons.Default.Info, expandedGroup == "about", !busy,
                onToggle = { expandedGroup = if (expandedGroup == "about") null else "about" }) {
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
