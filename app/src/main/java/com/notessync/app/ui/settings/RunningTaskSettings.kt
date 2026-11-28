package com.notessync.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.SettingsState
import com.notessync.app.tasks.running.RunningTaskNotifications

@Composable
internal fun RunningTaskSettings(state: SettingsState, settings: SettingsRepository) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(RunningTaskNotifications.canNotify(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = RunningTaskNotifications.canNotify(context)
        RunningTaskNotifications.request(context)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            allowed = RunningTaskNotifications.canNotify(context)
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingsToggle(uiText("Идущая задача в шторке"), state.runningTaskNotifications,
                settings::setRunningTaskNotifications, padded = false)
            if (state.runningTaskNotifications && !allowed) {
                Text(uiText("Уведомления выключены. Таймер продолжает работать в задаче."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        TextButton(onClick = { request.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(uiText("Разрешить")) }
                    }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, "running_tasks")) }
                    }) { Text(uiText("Настройки уведомлений")) }
                }
            }
            SettingsHelp("running-task-notifications", uiText("Как работает счётчик")) {
                Text(uiText("Android ведёт счётчик в уведомлении без постоянного сервиса приложения. Формат живого счётчика задаёт система; настройка часов и дней применяется к времени внутри NoteLS."))
                Text(uiText("До 20 идущих задач. Таймер и секундомер показаны отдельно. Пауза и выполнение доступны для записей без защиты; защищённые открываются через приложение."))
                Text(uiText("Принудительная остановка, перезагрузка, запрет уведомлений или ограничения Android могут скрыть уведомление. Оно восстанавливается при следующем входе; данные задачи сохраняются."))
            }
        }
    }
}
