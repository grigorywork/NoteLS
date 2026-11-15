package com.notessync.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.VoiceInputButton
import com.notessync.app.voice.VoiceState

/** Kept outside a focusable popup and the document scroll: stop/cancel remain reachable. */
@Composable
internal fun EditorVoicePanel(ui: EditorUiState, settings: SettingsState, repository: SettingsRepository,
    state: VoiceState, partial: String, enabled: Boolean, onStart: () -> Unit, onFinish: () -> Unit,
    onCancel: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("voice-panel")) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceInputButton(settings.voiceControl, state, onStart, onFinish, onCancel, enabled)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(when (state) {
                    VoiceState.IDLE -> uiText(if (settings.voiceControl == VoiceControl.HOLD) "Удерживайте микрофон и говорите" else "Нажмите микрофон, затем ещё раз для остановки")
                    VoiceState.STARTING -> uiText("Запуск микрофона…")
                    VoiceState.PROCESSING -> uiText("Распознаю…")
                    VoiceState.LISTENING -> partial.ifBlank { uiText("Слушаю… Говорите.") }
                }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(settings.voiceLanguage.label + " · " + uiText(if (settings.voiceEngine == VoiceEngine.LOCAL) "На устройстве" else "Онлайн"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = { ui.popup = EditorPopup.VOICE_OPTIONS }, enabled = enabled && state == VoiceState.IDLE) {
                    Icon(Icons.Default.Tune, uiText("Параметры диктовки"), Modifier.size(20.dp))
                }
                DropdownMenu(ui.popup == EditorPopup.VOICE_OPTIONS, { ui.dismissMenus() }) {
                    VoiceControl.entries.forEach { value ->
                        VoiceChoice(value.label, settings.voiceControl == value) { repository.setVoiceControl(value); ui.dismissMenus() }
                    }
                    HorizontalDivider()
                    VoiceLanguage.entries.forEach { value ->
                        VoiceChoice(value.label, settings.voiceLanguage == value) { repository.setVoiceLanguage(value); ui.dismissMenus() }
                    }
                    HorizontalDivider()
                    VoiceEngine.entries.forEach { value ->
                        VoiceChoice(uiText(if (value == VoiceEngine.LOCAL) "На устройстве" else "Онлайн"), settings.voiceEngine == value) {
                            repository.setVoiceEngine(value); ui.dismissMenus()
                        }
                    }
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, uiText("Закрыть диктовку"), Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun VoiceChoice(label: String, isSelected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, modifier = Modifier.semantics { selected = isSelected },
        leadingIcon = { RadioButton(isSelected, onClick = null) }, onClick = onClick)
}
