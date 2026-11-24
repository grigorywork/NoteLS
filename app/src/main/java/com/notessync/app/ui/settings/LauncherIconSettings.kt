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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherIconSettings() {
    val context = LocalContext.current
    val icons = remember(context.applicationContext) { LauncherIcons(context) }
    var selected by remember { mutableStateOf<LauncherIcon?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(icons) {
        try { selected = withContext(Dispatchers.IO) { icons.current() } }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).selectableGroup()) {
            Text(uiText("Значок приложения"), style = MaterialTheme.typography.titleSmall)
            Text(uiText("Выбери ярлык для рабочего стола. Его обновление может занять несколько секунд."),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            LauncherIcon.entries.forEach { icon ->
                val description = uiText("Значок: ") + uiText(icon.title)
                // Launcher artwork is large; settings decode only ~157px thumbnails off the UI thread.
                val preview by produceState<ImageBitmap?>(null, icon.artwork) {
                    value = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeResource(context.resources, icon.artwork,
                            BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("launcher-icon-${icon.name.lowercase()}")
                    .semantics { contentDescription = description }
                    .selectable(selected == icon, enabled = !busy, role = Role.RadioButton, onClick = {
                        busy = true; failed = false
                        scope.launch {
                            try { selected = withContext(Dispatchers.IO) { icons.select(icon) } }
                            catch (_: Exception) { failed = true }
                            finally { busy = false }
                        }
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) {
                        preview?.let { Image(it, null, Modifier.fillMaxSize()) }
                    }
                    Text(uiText(icon.title), Modifier.weight(1f))
                    RadioButton(selected == icon, onClick = null, enabled = !busy)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("launcher-icon-busy"))
            if (failed) Text(uiText("Не удалось сменить значок. Попробуй ещё раз."),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("launcher-icon-error"))
        }
    }
}
