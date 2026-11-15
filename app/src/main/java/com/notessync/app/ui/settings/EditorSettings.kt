package com.notessync.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.*

@Composable
internal fun EditorSettings(state: SettingsState, settings: SettingsRepository) {
    SectionTitle(uiText("Заметки и задачи"))
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("Выполненные — в отдельный раздел"), state.archiveCompletedTasks, settings::setArchiveCompletedTasks)
        SettingsToggle(uiText("Сразу выбирать время быстрой задачи"), state.quickTaskTime, settings::setQuickTaskTime)
        SettingsToggle(uiText("Флажки приоритета"), state.showTaskPriorities, settings::setShowTaskPriorities)
        SettingsDropdown("completed", uiText("Выполненные пункты"), state.completedStyle, CompletedStyle.entries, settings::setCompletedStyle, {
            if (it == CompletedStyle.STRIKE) uiText("Зачёркнутые") else uiText("Скрывать")
        })
        SettingsHelp("completed", uiText("О выполненных задачах")) {
            Text(uiText("Скрывать завершённые задачи в основных списках заметок. Снятие галочки возвращает запись."), style = MaterialTheme.typography.bodySmall)
            Text(uiText("В режиме правки выполненные всегда остаются видимыми — зелёными и зачёркнутыми."), style = MaterialTheme.typography.bodySmall)
        }
    }
    SectionTitle(uiText("Инструменты редактора"))
    SettingsCard(Modifier.fillMaxWidth()) {
        SettingsToggle(uiText("История изменений"), state.editorHistory, settings::setEditorHistory)
        SettingsToggle(uiText("Расширенное оформление"), state.advancedFormatting, settings::setAdvancedFormatting)
        SettingsToggle(uiText("Показывать таблицы"), state.showTables, settings::setShowTables)
        SettingsToggle(uiText("Камера в редакторе"), state.cameraCapture, settings::setCameraCapture)
        SettingsToggle(uiText("Размеры фото"), state.photoResize, settings::setPhotoResize)
    }
}
