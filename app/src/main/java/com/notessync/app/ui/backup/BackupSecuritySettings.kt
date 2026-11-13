package com.notessync.app.ui.backup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.ui.settings.SettingsCard

@Composable
internal fun BackupSecuritySettings(owner: BackupCoordinator) {
    val available = backupBiometricsAvailable(LocalContext.current)
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Защита резервной копии"), style = MaterialTheme.typography.titleMedium)
            Text(uiText(if (owner.identity == null) "Задайте пароль: до настройки автоматические копии не создаются."
                else "Копии зашифрованы по умолчанию. Пароль нужен для переноса на другой телефон."))
            Text(uiText("Системное копирование Android отключено. Сохраните пароль: без него и доступа на этом телефоне восстановить копию нельзя."),
                style = MaterialTheme.typography.bodySmall)
            if (!owner.loaded) Text(uiText("Защита ещё не загружена. Откройте настройки повторно."))
            TextButton(enabled = owner.loaded && !owner.busy, onClick = {
                owner.passwordMode = if (owner.identity == null) BackupPasswordMode.SETUP else BackupPasswordMode.CHANGE
            }) { Text(uiText(if (owner.identity == null) "Задать пароль копий" else "Изменить пароль копий")) }
            if (owner.identity != null) {
                Text(uiText("Биометрия действует только на этом телефоне. Для её включения подтвердите пароль копий."), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !owner.busy && (available || owner.biometricEnabled), onClick = {
                    if (owner.biometricEnabled) owner.disableBiometric() else owner.passwordMode = BackupPasswordMode.ENROLL
                }) { Text(uiText(if (owner.biometricEnabled) "Отключить отпечаток для копий" else "Включить отпечаток для копий")) }
            }
            if (owner.copies.any { !it.info.encrypted }) {
                Text(uiText("На устройстве остались старые незашифрованные копии."), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = owner.identity != null && !owner.busy, onClick = owner::protectOld) { Text(uiText("Зашифровать старые копии")) }
            }
        }
    }
}
