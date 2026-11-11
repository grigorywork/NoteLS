package com.notessync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.ThemeMode
import com.notessync.app.ui.navigation.AppNavHost
import com.notessync.app.ui.theme.NoteSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository.get(this)
        setContent {
            val state by settings.state.collectAsStateWithLifecycle()
            NoteSyncTheme(
                darkTheme = when (state.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                accentColor = state.accentColor,
                fontScale = state.fontScale,
                appFont = state.appFont,
                interfaceStyle = state.interfaceStyle
            ) {
                AppNavHost()
            }
        }
    }
}
