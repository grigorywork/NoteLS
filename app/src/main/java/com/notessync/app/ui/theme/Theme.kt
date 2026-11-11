package com.notessync.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.notessync.app.settings.AccentColor
import com.notessync.app.settings.AppFont
import com.notessync.app.settings.InterfaceStyle

// Запасная палитра для устройств без динамического цвета (Android < 12).
private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8)
)

/**
 * Акцентные палитры (Этап 29): primary + контейнеры для светлой и тёмной темы.
 * Остальные слоты — стандартные Material, чтобы компоненты не ломались.
 */
private data class AccentPalette(
    val lightPrimary: Color, val lightContainer: Color, val lightOnContainer: Color,
    val darkPrimary: Color, val darkContainer: Color, val darkOnContainer: Color
)

private val AccentPalettes = mapOf(
    AccentColor.VIOLET to AccentPalette(
        Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFF21005D),
        Color(0xFFD0BCFF), Color(0xFF4F378B), Color(0xFFEADDFF)
    ),
    AccentColor.BLUE to AccentPalette(
        Color(0xFF0061A4), Color(0xFFD1E4FF), Color(0xFF001D36),
        Color(0xFF9ECAFF), Color(0xFF00497D), Color(0xFFD1E4FF)
    ),
    AccentColor.GREEN to AccentPalette(
        Color(0xFF006E1C), Color(0xFF94F990), Color(0xFF002204),
        Color(0xFF78DC77), Color(0xFF005313), Color(0xFF94F990)
    ),
    AccentColor.ORANGE to AccentPalette(
        Color(0xFF964900), Color(0xFFFFDCC2), Color(0xFF311300),
        Color(0xFFFFB877), Color(0xFF713700), Color(0xFFFFDCC2)
    ),
    AccentColor.PINK to AccentPalette(
        Color(0xFF9C4054), Color(0xFFFFD9DF), Color(0xFF3F0015),
        Color(0xFFFFB1C0), Color(0xFF7B2940), Color(0xFFFFD9DF)
    ),
    AccentColor.TEAL to AccentPalette(
        Color(0xFF006874), Color(0xFF97F0FF), Color(0xFF001F24),
        Color(0xFF4FD8EB), Color(0xFF004F58), Color(0xFF97F0FF)
    )
)

private fun accentScheme(accent: AccentColor, dark: Boolean) = AccentPalettes[accent]?.let { p ->
    if (dark) {
        DarkColors.copy(
            primary = p.darkPrimary,
            primaryContainer = p.darkContainer,
            onPrimaryContainer = p.darkOnContainer,
            tertiary = p.darkPrimary,
            tertiaryContainer = p.darkContainer,
            onTertiaryContainer = p.darkOnContainer
        )
    } else {
        LightColors.copy(
            primary = p.lightPrimary,
            primaryContainer = p.lightContainer,
            onPrimaryContainer = p.lightOnContainer,
            tertiary = p.lightPrimary,
            tertiaryContainer = p.lightContainer,
            onTertiaryContainer = p.lightOnContainer
        )
    }
}

/** Цвет-точка для выбора акцента в настройках. */
fun accentPreviewColor(accent: AccentColor): Color = when (accent) {
    AccentColor.DYNAMIC -> Color(0xFF9E9E9E)
    else -> AccentPalettes.getValue(accent).lightPrimary
}

@Composable
fun NoteSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    accentColor: AccentColor = AccentColor.DYNAMIC,
    fontScale: Float = 1.0f,
    appFont: AppFont = AppFont.SYSTEM,
    interfaceStyle: InterfaceStyle = InterfaceStyle.CLASSIC,
    // Транслучентные активности (быстрая заметка, настройки виджета) передают false:
    // тогда сквозь стеклянную панель просвечивает сам рабочий стол — как в iOS.
    glassBackdrop: Boolean = true,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        accentColor != AccentColor.DYNAMIC -> accentScheme(accentColor, darkTheme)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    } ?: if (darkTheme) DarkColors else LightColors

    // Этап 37: матовое стекло — палитра становится полупрозрачной,
    // под контентом рисуется аврора в цвет акцента.
    val glass = interfaceStyle == InterfaceStyle.GLASS
    val colorScheme = if (glass) baseScheme.glassified(darkTheme) else baseScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography(fontScale, appFont)
    ) {
        CompositionLocalProvider(
            LocalGlassTheme provides GlassTheme(
                enabled = glass,
                dark = darkTheme,
                borderColor = glassBorderColor(darkTheme)
            )
        ) {
            if (glass && glassBackdrop) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AuroraBackground(dark = darkTheme, accent = colorScheme.primary)
                    content()
                }
            } else {
                content()
            }
        }
    }
}
