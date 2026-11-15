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
    private var widgetSequence = 0L
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetRequests.value = WidgetNoteLinks.read(intent, ++widgetSequence)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) widgetRequests.value = WidgetNoteLinks.read(intent, ++widgetSequence)
        val settings = SettingsRepository.get(this)
        setContent {
            val state by settings.state.collectAsStateWithLifecycle()
            val widgetRequest by widgetRequests.collectAsState()
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
                AppNavHost(widgetRequest) { handled ->
                    if (widgetRequests.value?.sequence == handled) widgetRequests.value = null
                }
            }
        }
    }
}
