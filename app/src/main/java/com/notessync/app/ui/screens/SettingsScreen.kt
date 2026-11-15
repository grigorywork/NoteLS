package com.notessync.app.ui.screens

import com.notessync.app.ui.settings.*
import com.notessync.app.ui.backup.*
import com.notessync.app.i18n.uiText
import com.notessync.app.i18n.UiLanguage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notessync.app.BuildConfig
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSync: () -> Unit, initialGroup: String? = null) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val state by settings.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val backup = rememberBackupCoordinator(settings, snackbarHostState)
    val busy = backup.busy
    var expandedGroup by rememberSaveable(initialGroup) { mutableStateOf(initialGroup?.takeIf { it == "transform" }) }
    var showDrawerQuote by remember { mutableStateOf(false) }
    var showFontLicenses by remember { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Finish the database operation before navigating away. */ }
    BackHandler(enabled = !busy && expandedGroup != null) { expandedGroup = null }
    if (showDrawerQuote) com.notessync.app.ui.quotes.DrawerQuoteDialog(state.drawerQuote,
        onSave = { settings.setDrawerQuoteAppearance(it); showDrawerQuote = false }, onDismiss = { showDrawerQuote = false })
    if (showFontLicenses) FontLicensesDialog(onDismiss = { showFontLicenses = false })

    LaunchedEffect(state.lastAutoBackupAt, backup.showCopies, expandedGroup) {
        if (expandedGroup == "backup" || backup.showCopies) backup.refresh()
    }
    val exportCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = backup.exportFile; backup.exportFile = null
        if (uri != null && file != null) backup.export(uri, file)
    }
    val createBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) backup.create(uri)
    }
    val openBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backup.select(uri)
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
            SettingsCard(Modifier.fillMaxWidth()) {
                SettingsDropdown("language", uiText("Язык интерфейса"), state.uiLanguage, UiLanguage.entries,
                    settings::setUiLanguage, { it.nativeName })
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
                BackupSecuritySettings(backup)
                BackupSettings(state, settings, busy, backup.copies.size, { createBackupLauncher.launch(it) }, { openBackupLauncher.launch(it) }, { backup.showCopies = true }, backup.loaded && backup.identity != null)
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

    BackupDialogs(backup) { file ->
        backup.exportFile = file
        exportCopyLauncher.launch(BackupManager.suggestedFileName())
    }
}
