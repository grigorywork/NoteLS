package com.notessync.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * Этап 37: «Матовое стекло» — стиль Kimi на iPhone.
 *
 * Принцип: поверх мягкого градиента-авроры лежат полупрозрачные панели
 * (вся палитра surfaceContainer* переводится в «стекло» через [glassified]),
 * у панелей тонкая световая кромка [glassBorderColor] и крупные скругления
 * [GlassPanelShape]. Слои выше — плотнее, чтобы текст всегда читался.
 */

/** Скругление панелей в стиле iOS. */
val GlassPanelCorner = 22.dp
val GlassPanelShape: Shape = RoundedCornerShape(GlassPanelCorner)

/** Прозрачности слоёв «стекла»: чем выше слой, тем плотнее. */
object GlassAlphas {
    const val LOWEST = 0.32f
    const val LOW = 0.48f
    const val MEDIUM = 0.66f
    const val HIGH = 0.78f
    const val HIGHEST = 0.86f
    const val VARIANT = 0.52f
}

/** Текущее состояние стекла в дереве композиции. */
data class GlassTheme(
    val enabled: Boolean = false,
    val dark: Boolean = false,
    val borderColor: Color = Color.Unspecified
)

val LocalGlassTheme = staticCompositionLocalOf { GlassTheme() }

/** База «стекла»: светлая тема — молочно-белое, тёмная — глубокое сине-графитовое. */
fun glassBaseColor(dark: Boolean): Color =
    if (dark) Color(0xFF14161F) else Color(0xFFFFFFFF)

/** Тонкая световая кромка панели — блик, как у настоящего стекла. */
fun glassBorderColor(dark: Boolean): Color =
    if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.65f)

/** Фон-подложка под авророй. */
fun auroraBaseColor(dark: Boolean): Color =
    if (dark) Color(0xFF0B0D14) else Color(0xFFF6F7FB)

/**
 * Четыре цветных «пятна» авроры. Первое — из акцента темы,
 * остальные — мягкие пастельные (светлая) / глубокие (тёмная).
 */
fun auroraBlobColors(dark: Boolean, accent: Color): List<Color> =
    if (dark) {
        listOf(
            accent.copy(alpha = 0.35f),
            Color(0xFF4A6CF7).copy(alpha = 0.28f),
            Color(0xFF9D4EDD).copy(alpha = 0.24f),
            Color(0xFF2DD4BF).copy(alpha = 0.20f)
        )
    } else {
        listOf(
            accent.copy(alpha = 0.30f),
            Color(0xFFA5C8FF).copy(alpha = 0.55f),
            Color(0xFFF3C1E8).copy(alpha = 0.50f),
            Color(0xFFBFEFE0).copy(alpha = 0.50f)
        )
    }

/**
 * Переводит палитру в «стекло»: фон и базовая поверхность прозрачные
 * (сквозь них видна аврора), слои surfaceContainer* — полупрозрачные,
 * оттенок тонирования убран, чтобы цвета не мутнели.
 * primary/onSurface и прочие слоты не трогаем — акценты и текст как в теме.
 */
fun ColorScheme.glassified(dark: Boolean): ColorScheme {
    val base = glassBaseColor(dark)
    return copy(
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceContainerLowest = base.copy(alpha = GlassAlphas.LOWEST),
        surfaceContainerLow = base.copy(alpha = GlassAlphas.LOW),
        surfaceContainer = base.copy(alpha = GlassAlphas.MEDIUM),
        surfaceContainerHigh = base.copy(alpha = GlassAlphas.HIGH),
        surfaceContainerHighest = base.copy(alpha = GlassAlphas.HIGHEST),
        surfaceBright = base.copy(alpha = GlassAlphas.MEDIUM),
        surfaceDim = base.copy(alpha = GlassAlphas.LOWEST),
        surfaceVariant = base.copy(alpha = GlassAlphas.VARIANT),
        surfaceTint = Color.Transparent
    )
}

/** Кромка панели в режиме стекла; в классике — без изменений. */
fun Modifier.glassBorder(shape: Shape = GlassPanelShape): Modifier = composed {
    val glass = LocalGlassTheme.current
    if (!glass.enabled) this else this.border(1.dp, glass.borderColor, shape)
}

/** Ослабляет сплошной цвет до «стеклянного», когда режим включён. */
@Composable
fun Color.glassified(alpha: Float = 0.55f): Color =
    if (LocalGlassTheme.current.enabled) copy(alpha = alpha) else this

private fun DrawScope.drawBlob(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to color,
            1.0f to color.copy(alpha = 0f),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * Мягкий градиент-аврора на весь экран: база + четыре цветных пятна
 * с радиальным затуханием (без blur — мягкость даёт сам градиент, лагов нет).
 */
@Composable
fun AuroraBackground(dark: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val base = auroraBaseColor(dark)
    val blobs = auroraBlobColors(dark, accent)
    Box(
        modifier = modifier.fillMaxSize().drawBehind {
            drawRect(base)
            val w = size.width
            val h = size.height
            drawBlob(blobs[0], Offset(w * 0.12f, h * 0.10f), w * 0.85f)
            drawBlob(blobs[1], Offset(w * 0.95f, h * 0.22f), w * 0.70f)
            drawBlob(blobs[2], Offset(w * 0.80f, h * 0.92f), w * 0.75f)
            drawBlob(blobs[3], Offset(w * 0.05f, h * 0.78f), w * 0.60f)
        }
    )
}
