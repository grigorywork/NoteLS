package com.notessync.app.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.launcher.LauncherIcon
import com.notessync.app.launcher.LauncherIcons
import com.notessync.app.launcher.LauncherIconSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherIconSettings() {
    val context = LocalContext.current
    val icons = remember(context.applicationContext) { LauncherIcons(context) }
    val scope = rememberCoroutineScope()
    val selection = remember(icons, scope) { LauncherIconSelection(icons, scope) }
    val choice by selection.state.collectAsState()
    LaunchedEffect(selection) { selection.load() }
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).selectableGroup()) {
            Text(uiText("Значок приложения"), style = MaterialTheme.typography.titleSmall)
            Text(uiText("Выбери ярлык для рабочего стола. Его обновление может занять несколько секунд."),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            LauncherIcon.entries.forEach { icon ->
                val description = uiText("Значок: ") + uiText(icon.title)
                // Launcher artwork is large; settings decode only ~157px thumbnails off the UI thread.
                var preview by remember(icon.artwork) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(icon.artwork) {
                    preview = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeResource(context.resources, icon.artwork,
                            BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("launcher-icon-${icon.name.lowercase()}")
                    .semantics { contentDescription = description }
                    .selectable(choice.selected == icon, enabled = !choice.busy, role = Role.RadioButton,
                        onClick = selection.action(icon, choice)).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) {
                        preview?.let { Image(it, null, Modifier.fillMaxSize()) }
                    }
                    Text(uiText(icon.title), Modifier.weight(1f))
                    RadioButton(choice.selected == icon, onClick = null, enabled = !choice.busy)
                }
            }
            if (choice.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("launcher-icon-busy"))
            if (choice.failed) Text(uiText("Не удалось сменить значок. Попробуй ещё раз."),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("launcher-icon-error"))
        }
    }
}
