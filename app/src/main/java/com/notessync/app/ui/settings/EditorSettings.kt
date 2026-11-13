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
import com.notessync.app.settings.CompletedStyle
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.SettingsState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorSettings(state: SettingsState, settings: SettingsRepository) {
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Сразу выбирать время быстрой задачи"), state.quickTaskTime, settings::setQuickTaskTime)
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Камера в редакторе"), state.cameraCapture, settings::setCameraCapture)
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Размеры фото"), state.photoResize, settings::setPhotoResize)
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Показывать таблицы"), state.showTables, settings::setShowTables)
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("История изменений"), state.editorHistory, settings::setEditorHistory)
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Расширенное оформление"), state.advancedFormatting, settings::setAdvancedFormatting)
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
}
