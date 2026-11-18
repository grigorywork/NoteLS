package com.notessync.app.ui.settings

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.VoiceControl
import com.notessync.app.settings.VoiceEngine
import com.notessync.app.settings.VoiceLanguage
import com.notessync.app.settings.VoiceStructure
import com.notessync.app.settings.SettingsState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceSettings(state: SettingsState, settings: SettingsRepository) {
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
            Text(uiText("Язык распознавания"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)) {
                VoiceLanguage.entries.forEach { language ->
                    SettingsChoice(selected = state.voiceLanguage == language, onClick = { settings.setVoiceLanguage(language) },
                        label = { Text(language.label) })
                }
            }
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
                uiText("При паузах диктовка продолжается. Отпустите микрофон или нажмите ещё раз для остановки. Уже распознанный текст сохраняется. Структуризация — локальные правила, без отдельного ИИ-сервиса."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    SectionTitle(uiText("Аудиовложения"))
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingsToggle(uiText("Запись аудио в заметку"), state.audioRecording, settings::setAudioRecording,
                modifier = Modifier.padding(bottom = 4.dp), padded = false)
            Text(uiText("До 20 минут и 10 МиБ на файл. Выключение скрывает запись; готовые аудиовложения остаются доступны."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
