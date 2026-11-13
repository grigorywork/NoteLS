package com.notessync.app.ui.screens

import com.notessync.app.i18n.uiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.sync.android.StorageInfo
import com.notessync.app.sync.android.SyncMode
import com.notessync.app.sync.android.SyncUiState
import com.notessync.app.sync.android.SyncViewModel
import com.notessync.app.sync.core.SyncPhase
import com.notessync.app.sync.crypto.PairingSecret
import com.notessync.app.sync.qr.SyncQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.notessync.app.ui.common.formatBytes
import com.notessync.app.ui.common.formatDateTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit, onOpenScanner: () -> Unit, viewModel: SyncViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var storage by remember(viewModel) { mutableStateOf<StorageInfo?>(null) }
    LaunchedEffect(viewModel, state.mode) { storage = viewModel.storageInfo() }

    fun leave() { viewModel.cancel(); onBack() }
    fun scan() { viewModel.cancel(); onOpenScanner() }
    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiText("Синхронизация")) },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.mode) {
                SyncMode.IDLE -> IdleBlock(viewModel, ::scan)
                SyncMode.WAITING -> WaitingBlock(state, onCancel = viewModel::cancel)
                SyncMode.CLIENT_FORM -> ClientFormBlock(onConnect = viewModel::connect, onOpenScanner = ::scan)
                SyncMode.SYNCING -> SyncingBlock(state)
                SyncMode.DONE -> DoneBlock(state, onDone = viewModel::dismissResult)
                SyncMode.ERROR -> ErrorBlock(state, onRetry = viewModel::dismissResult)
            }
            if (state.mode in setOf(SyncMode.IDLE, SyncMode.DONE, SyncMode.ERROR)) viewModel.automatic?.let {
                Spacer(Modifier.height(16.dp))
                com.notessync.app.sync.auto.AutoSyncControls(it, allowReset = state.mode == SyncMode.ERROR)
            }
            Spacer(Modifier.height(24.dp))
            storage?.let { StorageCard(it) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IdleBlock(viewModel: SyncViewModel, onOpenScanner: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = uiText("Обмен по Wi-Fi, без облака"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = viewModel::startWaiting,
        modifier = Modifier.fillMaxWidth()
    ) { Text(uiText("Показать QR-код")) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onOpenScanner,
        modifier = Modifier.fillMaxWidth()
    ) { Text(uiText("Сканировать QR-код")) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = viewModel::showClientForm,
        modifier = Modifier.fillMaxWidth()
    ) { Text(uiText("Ввести код вручную")) }
}

@Composable
private fun WaitingBlock(state: SyncUiState, onCancel: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(16.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // QR-код с адресом, портом и кодом сессии (Этап 4).
            if (state.qrPayload.isNotBlank()) {
                var qrBitmap by remember(state.qrPayload) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(state.qrPayload) {
                    qrBitmap = withContext(Dispatchers.Default) { com.notessync.app.sync.qr.QrGenerator.bitmap(state.qrPayload) }
                }
                qrBitmap?.let { androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = uiText("QR-код для связи"),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = state.sessionCode,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            androidx.compose.material3.TextButton(onClick = {
                val clip = android.content.ClipData.newPlainText("NoteLS pairing", state.sessionCode)
                clip.description.extras = android.os.PersistableBundle().apply {
                    // Documented key also works with older SDKs; newer Android redacts the preview.
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            }) { Text(uiText("Копировать ключ")) }
            Text(
                text = state.localIp.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(uiText("Код действует 5 минут и даёт доступ к обмену всеми заметками, включая защищённые. Показывайте его только своему устройству."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Text(uiText("При поддержке обоими устройствами связь сохранится для последующего автообмена."), style = MaterialTheme.typography.bodySmall)
    CircularProgressIndicator()
    Spacer(Modifier.height(8.dp))
    Text(
        text = uiText("Ожидание подключения"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel) { Text(uiText("Отмена")) }
}

@Composable
private fun ClientFormBlock(onConnect: (String, String) -> Unit, onOpenScanner: () -> Unit) {
    var ip by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onOpenScanner,
        modifier = Modifier.fillMaxWidth()
    ) { Text(uiText("Сканировать QR-код")) }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = ip,
        onValueChange = { ip = it.take(15) },
        label = { Text(uiText("IP-адрес")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.trim().uppercase(Locale.ROOT).take(64) },
        label = { Text(uiText("Ключ сопряжения (64 символа)")) },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { onConnect(ip, code) },
        enabled = SyncQr.localAddress(ip) && PairingSecret.valid(code),
        modifier = Modifier.fillMaxWidth()
    ) { Text(uiText("Начать синхронизацию")) }
}

@Composable
private fun SyncingBlock(state: SyncUiState) {
    val progress = state.progress
    Spacer(Modifier.height(32.dp))
    Text(
        text = phaseLabel(progress.phase),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
    if (progress.phase == SyncPhase.RECEIVING_FILES && progress.attachmentBytesExpected > 0) {
        val fraction =
            (progress.attachmentBytesReceived.toFloat() / progress.attachmentBytesExpected).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // Процент строго по центру — требование аудита.
        Text(
            text = "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = uiText("Файл %1\$s из %2\$s", (progress.attachmentsDone + 1), (progress.attachmentsTotal)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DoneBlock(state: SyncUiState, onDone: () -> Unit) {
    val result = state.result
    Spacer(Modifier.height(24.dp))
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Text(uiText("Готово"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (state.peerName.isNotBlank()) {
        Text(
            text = uiText("Связано с: %1\$s", (state.peerName)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
    state.trustNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
    if (result != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = uiText("Получено: %1\$s, отправлено: %2\$s", (result.receivedNotes), (result.sentNotes)) +
                if (result.conflicts > 0) uiText(", конфликты: %1\$s", (result.conflicts)) else "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(uiText("ОК")) }
}

@Composable
private fun ErrorBlock(state: SyncUiState, onRetry: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = uiText("Синхронизация не удалась"),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = state.error.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(uiText("Повторить")) }
}

@Composable
private fun StorageCard(storage: StorageInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = uiText("Хранилище"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent = { Text(uiText("База данных")) },
                trailingContent = { Text(formatBytes(storage.databaseBytes)) }
            )
            ListItem(
                headlineContent = { Text(uiText("Вложения")) },
                trailingContent = { Text(formatBytes(storage.attachmentBytes)) }
            )
            ListItem(
                headlineContent = { Text(uiText("Последняя синхронизация")) },
                trailingContent = { Text(formatDateTime(storage.lastSyncAt)) }
            )
        }
    }
}

private fun phaseLabel(phase: SyncPhase): String = when (phase) {
    SyncPhase.HANDSHAKE -> uiText("Подключение…")
    SyncPhase.EXCHANGING_CHANGES -> uiText("Обмен заметками…")
    SyncPhase.RECEIVING_FILES -> uiText("Приём файлов…")
    SyncPhase.SENDING_FILES -> uiText("Отправка файлов…")
    SyncPhase.FINISHING -> uiText("Завершение…")
    SyncPhase.COMPLETED -> uiText("Готово")
}

