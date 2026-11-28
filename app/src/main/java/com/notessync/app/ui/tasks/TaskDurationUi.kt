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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

val LocalTaskDurationStyle = compositionLocalOf { TaskDurationStyle() }

/** One event-driven settings subscription per themed root, not one per card or clock tick. */
@Composable
internal fun TaskDurationContent(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    val repository = remember(app) { SettingsRepository.get(app) }
    val styles = remember(repository) { repository.state.map {
        TaskDurationStyle(it.automaticDurationUnits, it.uiLanguage == UiLanguage.ENGLISH)
    }.distinctUntilChanged() }
    val initial = repository.state.value.let { TaskDurationStyle(it.automaticDurationUnits, it.uiLanguage == UiLanguage.ENGLISH) }
    val style by styles.collectAsStateWithLifecycle(initialValue = initial)
    CompositionLocalProvider(LocalTaskDurationStyle provides style, content = content)
}
