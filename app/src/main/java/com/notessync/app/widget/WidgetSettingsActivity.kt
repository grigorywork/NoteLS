package com.notessync.app.widget

import com.notessync.app.i18n.uiText

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.WidgetCompletedStyle
import com.notessync.app.ui.theme.GlassPanelShape
import com.notessync.app.ui.theme.LocalGlassTheme
import com.notessync.app.ui.theme.NoteSyncTheme
import com.notessync.app.ui.theme.glassBorder
import kotlin.math.roundToInt

/**
 * Настройки виджета-календаря (Этап 28).
 * Открывается кнопкой «⋮» в шапке виджета; полупрозрачный диалог поверх
 * рабочего стола в собственной задаче — при закрытии приложение не всплывает.
 */
class WidgetSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { SettingsRepository.get(this) }
            val state by settings.state.collectAsStateWithLifecycle()
            NoteSyncTheme(
                darkTheme = when (state.themeMode) {
                    com.notessync.app.settings.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    com.notessync.app.settings.ThemeMode.LIGHT -> false
                    com.notessync.app.settings.ThemeMode.DARK -> true
                },
                fontScale = state.fontScale,
                accentColor = state.accentColor,
                appFont = state.appFont,
                glassAppearance = state.glassAppearance,
                interfaceStyle = state.interfaceStyle,
                glassBackdrop = false
            ) {
                // Этап 37: стеклянная панель поверх рабочего стола.
                val glass = LocalGlassTheme.current
                Card(
                    shape = if (glass.enabled) GlassPanelShape else MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .padding(16.dp)
                        .imePadding()
                        .glassBorder()
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text(uiText("Виджет"), style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            uiText("Прозрачность фона"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsRepository.WIDGET_ALPHA_PRESETS.forEach { preset ->
                                val label = "${(preset * 100).roundToInt()}%"
                                val selected =
                                    kotlin.math.abs(state.widgetAlpha - preset) < 0.01f
                                if (selected) {
                                    Button(onClick = { }) { Text(label) }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            settings.setWidgetAlpha(preset)
                                            CalendarWidgetProvider.refreshAll(applicationContext)
                                        }
                                    ) { Text(label) }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Этап 33: цветная подсветка задач в ячейках календаря.
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(uiText("Цветные задачи"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    uiText("Каждая задача подсвечивается своим цветом, чтобы не сливаться с остальными"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = state.widgetColoredTasks,
                                onCheckedChange = { enabled ->
                                    settings.setWidgetColoredTasks(enabled)
                                    CalendarWidgetProvider.refreshAll(applicationContext)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            uiText("Выполненные задачи"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            uiText("Стиль применяется в окне дня и в ячейках календаря"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        WidgetCompletedStyle.entries.forEach { style ->
                            FilterChip(
                                selected = state.widgetCompletedStyle == style,
                                onClick = {
                                    settings.setWidgetCompletedStyle(style)
                                    CalendarWidgetProvider.refreshAll(applicationContext)
                                },
                                label = { Text(style.label) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(uiText("Закрыть")) }
                    }
                }
            }
        }
    }
}
