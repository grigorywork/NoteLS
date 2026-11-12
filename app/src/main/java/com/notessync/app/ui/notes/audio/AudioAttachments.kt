package com.notessync.app.ui.notes.audio

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notessync.app.audio.*
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.i18n.uiText
import com.notessync.app.ui.common.formatBytes
import com.notessync.app.ui.tasks.TaskTimer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AudioRecordingPanel(capture: AudioCapture, state: AudioCaptureState) {
    if (!state.busy) return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var now by remember(state.token) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.token, state.phase, lifecycle) {
        if (state.phase == AudioCapturePhase.RECORDING && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) }
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("audio-capture")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(when (state.phase) {
                AudioCapturePhase.RECORDING -> uiText("Запись аудио: %1\$s", TaskTimer.format((now - state.startedAt).coerceAtLeast(0)))
                AudioCapturePhase.PREPARING -> uiText("Подготовка записи…")
                else -> uiText("Сохранение аудио…")
            }, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { capture.finish() }, enabled = state.phase != AudioCapturePhase.SAVING) {
                Icon(Icons.Default.Stop, uiText("Сохранить аудио"))
            }
            IconButton(onClick = { capture.cancel() }, enabled = state.phase != AudioCapturePhase.SAVING) {
                Icon(Icons.Default.Close, uiText("Отменить запись"))
            }
        }
    }
}

@Composable
fun AudioAttachmentList(attachments: List<AttachmentEntity>, playback: AudioPlayback,
    onRemove: (AttachmentEntity) -> Unit, onMessage: (String) -> Unit) {
    if (attachments.isEmpty()) return
    val state by playback.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.failed, state.key) {
        if (state.failed) { onMessage(uiText("Не удалось воспроизвести аудио. Проверьте файл и повторите попытку.")); playback.clearError() }
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments, key = { it.syncId }) { attachment ->
            val selected = state.key == attachment.syncId
            val active = selected && state.active
            OutlinedCard(Modifier.width(280.dp).testTag("audio-${attachment.syncId}")) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (active) playback.pause() else playback.play(attachment.syncId)
                        }) { Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (active) uiText("Пауза") else uiText("Воспроизвести аудио")) }
                        Column(Modifier.weight(1f)) {
                            Text(uiText("Аудиозапись"), style = MaterialTheme.typography.titleSmall)
                            Text(audioDate(attachment.createdAt) + " · " + formatBytes(attachment.sizeBytes),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemove(attachment) }) { Icon(Icons.Default.Delete, uiText("Удалить аудио")) }
                    }
                    if (selected) {
                        if (state.phase == AudioPlaybackPhase.PREPARING) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { if (state.durationMillis <= 0) 0f else state.positionMillis.toFloat() / state.durationMillis }, modifier = Modifier.fillMaxWidth())
                            Text(TaskTimer.format(state.positionMillis) + " / " + TaskTimer.format(state.durationMillis), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

fun captureMessage(message: AudioCaptureMessage): String = when (message) {
    AudioCaptureMessage.PERMISSION -> uiText("Нужно разрешение на микрофон")
    AudioCaptureMessage.LOW_STORAGE -> uiText("Недостаточно места для аудиозаписи. Освободите не менее 12 МиБ.")
    AudioCaptureMessage.RECORD_FAILED -> uiText("Не удалось начать запись звука. Проверьте микрофон и повторите попытку.")
    AudioCaptureMessage.SAVE_FAILED -> uiText("Не удалось сохранить аудио. Проверьте свободное место, запись и доступ к заметке.")
    AudioCaptureMessage.LIMIT_SAVED -> uiText("Достигнут предел записи. Аудио сохранено.")
    AudioCaptureMessage.INTERRUPTED_SAVED -> uiText("Запись прервана системой. Аудио сохранено.")
}
private val audioDateFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm")
private fun audioDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(audioDateFormat)
