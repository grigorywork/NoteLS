package com.notessync.app.ui.settings

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.settings.BackupFrequency
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.settings.SettingsState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupSettings(state: SettingsState, settings: SettingsRepository, busy: Boolean, localCopiesCount: Int, onCreate: (String) -> Unit, onRestore: (Array<String>) -> Unit, onCopies: () -> Unit, configured: Boolean) {
    // Этап 39: резервное копирование — ручной бэкап/восстановление
    // и отдельная настройка частоты автобэкапа.
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        SettingsItem(
            headlineContent = { Text(uiText("Сохранить резервную копию")) },
            divider = true,
            supportingContent = { Text(uiText("Зашифрованный файл: заметки, задачи, блокноты и фото")) },
            leadingContent = {
                Icon(
                    Icons.Default.Backup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy && configured) { onCreate(BackupManager.suggestedFileName()) }
        )
        SettingsItem(
            headlineContent = { Text(uiText("Восстановить из файла")) },
            divider = true,
            supportingContent = { Text(uiText("Заменить текущие данные содержимым архива")) },
            leadingContent = {
                Icon(
                    Icons.Default.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) {
                    onRestore(
                        arrayOf("application/zip", "application/octet-stream")
                    )
                }
        )
        SettingsItem(
            headlineContent = { Text(uiText("Копии на этом устройстве")) },
            divider = true,
            supportingContent = { Text(uiText("Доступно: %1\$s. Восстановить или сохранить в папку", (localCopiesCount))) },
            leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onCopies() }
        )
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsDropdown("backup-frequency", uiText("Автоматические копии"), state.backupFrequency,
                BackupFrequency.entries, settings::setBackupFrequency, { it.label }, enabled = !busy)
            Text(
                if (state.lastBackupAt > 0) {
                    uiText("Последний бэкап: %1\$s", (formatDateTime(state.lastBackupAt)))
                } else {
                    uiText("Бэкап ещё не создавался")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                uiText("Хранятся 5 последних копий на устройстве. Проверка — при открытии и закрытии приложения, ") +
                    uiText("без постоянно работающей службы. «После каждой заметки» — при выходе из редактора. ") +
                    uiText("При удалении приложения локальные копии тоже удаляются: важную копию сохраните в выбранную папку."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
