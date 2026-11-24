package com.notessync.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.launcher.AndroidPlannerShortcut
import com.notessync.app.launcher.PlannerShortcutController

@Composable
internal fun PlannerShortcutSettings(controller: PlannerShortcutController? = null) {
    val context = LocalContext.current.applicationContext
    val platform = controller ?: remember(context) { AndroidPlannerShortcut(context) }
    val supported = remember(platform) { platform.isSupported() }
    var requested by remember { mutableStateOf<Boolean?>(null) }
    SettingsCard(Modifier.fillMaxWidth().testTag("planner-shortcut-settings")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp))
                Text(uiText("Ярлык планировщика"), style = MaterialTheme.typography.titleSmall)
            }
            Text(uiText("Открывает календарь одним нажатием."), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { requested = platform.request() }, enabled = supported,
                modifier = Modifier.testTag("pin-planner-shortcut")) { Text(uiText("Добавить на главный экран")) }
            if (!supported) Text(uiText("Лаунчер не поддерживает добавление ярлыка из приложения."), style = MaterialTheme.typography.bodySmall)
            requested?.let { success -> Text(uiText(if (success) "Запрос на добавление ярлыка отправлен."
                else "Не удалось запросить ярлык. Попробуй ещё раз."), style = MaterialTheme.typography.bodySmall,
                color = if (success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
        }
    }
}
