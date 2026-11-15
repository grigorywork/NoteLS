package com.notessync.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.*

@Composable
internal fun VoiceSettings(state: SettingsState, settings: SettingsRepository) {
    SectionTitle(uiText("Голосовой ввод"))
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsDropdown("voice-engine", uiText("Распознавание речи"), state.voiceEngine, VoiceEngine.entries, settings::setVoiceEngine, {
            uiText(if (it == VoiceEngine.ONLINE) "Онлайн" else "На устройстве")
        })
        Text(uiText(if (state.voiceEngine == VoiceEngine.ONLINE) "Звук передаётся системному сервису распознавания."
            else "Локально: Android 12+ и модель на устройстве. Без перехода в онлайн."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        SettingsDropdown("voice-language", uiText("Язык распознавания"), state.voiceLanguage, VoiceLanguage.entries, settings::setVoiceLanguage, { it.label })
        SettingsDropdown("voice-control", uiText("Микрофон"), state.voiceControl, VoiceControl.entries, settings::setVoiceControl, { it.label })
        SettingsDropdown("voice-structure", uiText("Структуризация текста"), state.voiceStructure, VoiceStructure.entries, settings::setVoiceStructure, { it.label })
        SettingsHelp("voice", uiText("О диктовке")) {
            Text(uiText("Онлайн-режим использует системный сервис: звук может передаваться его провайдеру. Локальный режим требует Android 12+ и доступную модель на устройстве; автоматического перехода в онлайн нет."), style = MaterialTheme.typography.bodySmall)
            Text(uiText("Удерживайте микрофон и говорите; отпустите, чтобы получить текст. Режим нажатий и обычная клавиатура доступны, если удерживать неудобно. Структуризация — локальные правила, без отдельного ИИ-сервиса."), style = MaterialTheme.typography.bodySmall)
        }
    }
    SectionTitle(uiText("Аудиовложения"))
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Запись аудио в заметку"), state.audioRecording, settings::setAudioRecording)
        SettingsHelp("audio", uiText("Об аудиовложениях")) {
            Text(uiText("До 20 минут и 10 МиБ на файл. Выключение скрывает запись; готовые аудиовложения остаются доступны."), style = MaterialTheme.typography.bodySmall)
        }
    }
}
