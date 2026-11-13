package com.notessync.app.sync.auto

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.sync.trust.PeerSummary
import com.notessync.app.ui.common.formatDateTime
import kotlinx.coroutines.launch

@Composable
internal fun AutoSyncControls(runtime: AutomaticSyncRuntime, allowReset: Boolean = false) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.get(context) }
    val config by settings.state.collectAsStateWithLifecycle()
    val trust by runtime.peers.state.collectAsStateWithLifecycle()
    val status by runtime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val autoLabel = uiText("Автосинхронизация")
    var busy by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<PeerSummary?>(null) }
    var reset by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    if (forget != null || reset) AlertDialog(
        onDismissRequest = { forget = null; reset = false },
        title = { Text(uiText(if (reset) "Сбросить все связи?" else "Забыть устройство?")) },
        text = { Text(if (reset) uiText("Заметки останутся. Потребуется новое QR-сопряжение.") else forget!!.name) },
        confirmButton = { TextButton(onClick = {
            val id = forget?.id; val all = reset
            forget = null; reset = false; busy = true; failed = false
            scope.launch {
                try { if (all) runtime.resetConnections() else if (id != null) runtime.forget(id) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(uiText("Подтвердить")) } },
        dismissButton = { TextButton(onClick = { forget = null; reset = false }) { Text(uiText("Отмена")) } }
    )
    OutlinedCard(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.25.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(uiText("Автосинхронизация"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(modifier = Modifier.semantics { contentDescription = autoLabel }, checked = config.autoSyncEnabled, onCheckedChange = settings::setAutoSyncEnabled, enabled = !busy)
            }
            Text(uiText("Обмен всеми заметками, включая защищённые. NoteLS должен быть открыт на обоих устройствах в одной Wi-Fi-сети."), style = MaterialTheme.typography.bodySmall)
            Text(uiText("Автообмен выполняется после закрытия редакторов."), style = MaterialTheme.typography.bodySmall)
            Text(autoStatusLabel(status), style = MaterialTheme.typography.bodyMedium)
            if (failed) Text(uiText("Не удалось изменить связи. Повторите попытку."), color = MaterialTheme.colorScheme.error)
            trust.peers.forEach { peer ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name.ifBlank { uiText("Устройство") })
                        if (peer.lastSyncAt > 0) Text(formatDateTime(peer.lastSyncAt), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { forget = peer }, enabled = !busy) {
                        Icon(Icons.Default.Delete, uiText("Забыть %1\$s", peer.name))
                    }
                }
            }
            if (config.autoSyncEnabled && (trust.peers.isNotEmpty() || !trust.available)) {
                TextButton(onClick = runtime::syncNow, enabled = !busy && status.phase != AutoPhase.SYNCING) { Text(uiText("Повторить проверку")) }
            }
            if (trust.peers.isNotEmpty() || !trust.available || allowReset) {
                TextButton(onClick = { reset = true }, enabled = !busy) { Text(uiText("Сбросить связи")) }
            }
        }
    }
}

@Composable
private fun autoStatusLabel(status: AutoStatus): String = when (status.phase) {
    AutoPhase.LOADING -> uiText("Загрузка подключений…")
    AutoPhase.PAUSED -> uiText("Автообмен приостановлен")
    AutoPhase.DISABLED -> uiText("Автообмен выключен")
    AutoPhase.PAIR_FIRST -> uiText("Сначала выполните QR-сопряжение")
    AutoPhase.NO_WIFI -> uiText("Подключитесь к локальной сети Wi-Fi")
    AutoPhase.WAITING -> uiText("Ожидание доверенного устройства")
    AutoPhase.SYNCING -> uiText("Обмен с %1\$s", status.peer)
    AutoPhase.SUCCESS -> uiText("Последний обмен: %1\$s", status.peer)
    AutoPhase.ERROR -> uiText("Ошибка обмена. Проверьте Wi-Fi и откройте NoteLS на втором устройстве.")
    AutoPhase.UNAVAILABLE -> uiText("Не удалось прочитать связи. Повторите проверку или сбросьте связи.")
}
