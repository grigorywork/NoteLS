package com.notessync.app.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository

val LocalTaskDurationStyle = compositionLocalOf { TaskDurationStyle() }

/** One event-driven settings subscription per themed root, not one per card or clock tick. */
@Composable
internal fun TaskDurationContent(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    val repository = remember(app) { SettingsRepository.get(app) }
    val state by repository.state.collectAsStateWithLifecycle()
    val style = remember(state.automaticDurationUnits, state.uiLanguage) {
        TaskDurationStyle(state.automaticDurationUnits, state.uiLanguage == UiLanguage.ENGLISH)
    }
    CompositionLocalProvider(LocalTaskDurationStyle provides style, content = content)
}
