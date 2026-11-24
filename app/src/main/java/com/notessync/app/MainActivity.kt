package com.notessync.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import com.notessync.app.widget.WidgetNoteLinks
import com.notessync.app.widget.WidgetNoteRequest
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.ThemeMode
import com.notessync.app.ui.navigation.AppNavHost
import com.notessync.app.ui.theme.NoteSyncTheme

class MainActivity : FragmentActivity() {
    private val widgetRequests = MutableStateFlow<WidgetNoteRequest?>(null)
    private val plannerRequests = MutableStateFlow<Long?>(null)
    private var widgetSequence = 0L
    private fun readRequests(intent: Intent) {
        val sequence = ++widgetSequence
        widgetRequests.value = WidgetNoteLinks.read(intent, sequence)
        plannerRequests.value = sequence.takeIf { com.notessync.app.launcher.PlannerShortcutLinks.matches(intent) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequests(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readRequests(intent)
        val settings = SettingsRepository.get(this)
        setContent {
            val state by settings.state.collectAsStateWithLifecycle()
            val widgetRequest by widgetRequests.collectAsState()
            val plannerRequest by plannerRequests.collectAsState()
            NoteSyncTheme(
                darkTheme = when (state.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                accentColor = state.accentColor,
                fontScale = state.fontScale,
                appFont = state.appFont,
                glassAppearance = state.glassAppearance,
                interfaceStyle = state.interfaceStyle
            ) {
                AppNavHost(widgetRequest, onWidgetHandled = { handled ->
                    if (widgetRequests.value?.sequence == handled) widgetRequests.value = null
                }, plannerRequest = plannerRequest, onPlannerHandled = { handled ->
                    if (plannerRequests.value == handled) plannerRequests.value = null
                })
            }
        }
    }
}
