package com.notessync.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.*
import com.notessync.app.ui.theme.NoteSyncTheme
import com.notessync.app.ui.theme.glassBaseColor
import kotlin.math.roundToInt

/** A disposable local draft. Neither reset, preview, Back nor Cancel writes preferences. */
@Composable
internal fun GlassSettingsDialog(state: SettingsState, onDismiss: () -> Unit, onSave: (GlassAppearance) -> Unit) {
    var transparency by rememberSaveable { mutableFloatStateOf(state.glassAppearance.safe().transparency) }
    var frost by rememberSaveable { mutableFloatStateOf(state.glassAppearance.safe().frost) }
    var previewFrost by rememberSaveable { mutableFloatStateOf(frost) }
    var tint by rememberSaveable { mutableStateOf(state.glassAppearance.tint) }
    var backdrop by rememberSaveable { mutableStateOf(state.glassAppearance.backdrop) }
    val draft = GlassAppearance(transparency, frost, tint, backdrop).safe()
    val dark = when (state.themeMode) {
        ThemeMode.DARK -> true; ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = glassBaseColor(dark),
        title = { Text(uiText("Настроить стекло")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(16.dp)).testTag("glass-preview")) {
                    NoteSyncTheme(darkTheme = dark, accentColor = state.accentColor, fontScale = state.fontScale,
                        appFont = state.appFont, interfaceStyle = InterfaceStyle.GLASS,
                        glassAppearance = draft.copy(frost = previewFrost)) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(uiText("Предпросмотр"), style = MaterialTheme.typography.labelMedium)
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(uiText("Заметки и идеи"), style = MaterialTheme.typography.titleMedium)
                                    Text(uiText("Чёткий текст на мягком фоне"), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                GlassSlider("transparency", uiText("Прозрачность"), transparency, { transparency = it })
                GlassSlider("frost", uiText("Матовость"), frost, { frost = it },
                    enabled = backdrop != GlassBackdrop.SOLID, onFinished = { previewFrost = frost })
                SettingsDropdown("glass-tint", uiText("Цвет фона"), tint, GlassTint.entries, { tint = it }, { it.label })
                SettingsDropdown("glass-backdrop", uiText("Рисунок фона"), backdrop, GlassBackdrop.entries,
                    { backdrop = it }, { it.label })
                if (backdrop == GlassBackdrop.SOLID) Text(uiText("Однотонный фон не требует размытия."),
                    style = MaterialTheme.typography.bodySmall)
                SettingsHelp("glass-rendering", uiText("Как работает стекло")) {
                    Text(uiText("Android 12+: размывается только фон приложения, текст остаётся чётким. На старых версиях — мягкий градиент без системного размытия. В быстрых окнах фон не размывается, панели плотнее. Меню сохраняют минимальную плотность для чтения."),
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    val defaults = GlassAppearance()
                    transparency = defaults.transparency; frost = defaults.frost; previewFrost = defaults.frost
                    tint = defaults.tint; backdrop = defaults.backdrop
                }, modifier = Modifier.testTag("glass-reset")) { Text(uiText("Сбросить")) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft); onDismiss() }, modifier = Modifier.testTag("glass-save")) {
            Text(uiText("Сохранить"))
        } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("glass-cancel")) { Text(uiText("Отмена")) } })
}

@Composable
private fun GlassSlider(id: String, label: String, value: Float, onChange: (Float) -> Unit,
    enabled: Boolean = true, onFinished: () -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value, onChange, enabled = enabled, onValueChangeFinished = onFinished, steps = 9,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("glass-$id")
                .semantics { contentDescription = label })
    }
}
