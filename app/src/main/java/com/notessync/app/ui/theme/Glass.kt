package com.notessync.app.ui.theme

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.notessync.app.settings.GlassAppearance
import com.notessync.app.settings.GlassBackdrop
import com.notessync.app.settings.GlassTint

val GlassPanelCorner = 22.dp
val GlassPanelShape: Shape = RoundedCornerShape(GlassPanelCorner)

/** Legacy layer opacity at the transparency slider midpoint. */
object GlassAlphas {
    const val LOWEST = 0.32f
    const val LOW = 0.48f
    const val MEDIUM = 0.66f
    const val HIGH = 0.78f
    const val HIGHEST = 0.86f
    const val VARIANT = 0.52f
}

data class GlassTheme(
    val enabled: Boolean = false,
    val dark: Boolean = false,
    val borderColor: Color = Color.Unspecified,
    val appearance: GlassAppearance = GlassAppearance(),
    val externalBackdrop: Boolean = false
)
val LocalGlassTheme = staticCompositionLocalOf { GlassTheme() }

fun glassBaseColor(dark: Boolean): Color = if (dark) Color(0xFF14161F) else Color.White
fun glassBorderColor(dark: Boolean): Color = Color.White.copy(alpha = if (dark) 0.14f else 0.65f)
fun auroraBaseColor(dark: Boolean): Color = if (dark) Color(0xFF0B0D14) else Color(0xFFF6F7FB)

/** Adaptive palettes stay pale in light mode and deep in dark mode, independently of accent. */
fun glassTintColor(tint: GlassTint, dark: Boolean): Color = when (tint) {
    GlassTint.THEME -> auroraBaseColor(dark)
    GlassTint.BLUE -> if (dark) Color(0xFF0A1425) else Color(0xFFEDF4FF)
    GlassTint.TEAL -> if (dark) Color(0xFF071B1E) else Color(0xFFEAF9FA)
    GlassTint.LILAC -> if (dark) Color(0xFF191126) else Color(0xFFF5EFFF)
    GlassTint.ROSE -> if (dark) Color(0xFF23121B) else Color(0xFFFFEFF5)
    GlassTint.AMBER -> if (dark) Color(0xFF211908) else Color(0xFFFFF6E6)
    GlassTint.GREEN -> if (dark) Color(0xFF101E14) else Color(0xFFEFF9EE)
    GlassTint.GRAPHITE -> if (dark) Color(0xFF15171B) else Color(0xFFF0F2F5)
}

fun auroraBlobColors(dark: Boolean, accent: Color): List<Color> = if (dark) listOf(
    accent.copy(alpha = 0.16f), Color(0xFF4A6CF7).copy(alpha = 0.14f),
    Color(0xFF9D4EDD).copy(alpha = 0.12f), Color(0xFF2DD4BF).copy(alpha = 0.10f)
) else listOf(
    accent.copy(alpha = 0.30f), Color(0xFFA5C8FF).copy(alpha = 0.55f),
    Color(0xFFF3C1E8).copy(alpha = 0.50f), Color(0xFFBFEFE0).copy(alpha = 0.50f)
)

/** Menus retain density; windows above unknown desktop content have a stronger floor. */
fun glassLayerAlpha(legacy: Float, appearance: GlassAppearance, externalBackdrop: Boolean = false): Float {
    val a = legacy.coerceIn(0f, 1f)
    val floor = when {
        a >= GlassAlphas.HIGHEST -> 0.82f
        a >= GlassAlphas.HIGH -> 0.72f
        a >= GlassAlphas.MEDIUM -> 0.55f
        a >= GlassAlphas.LOW -> 0.42f
        else -> minOf(a, 0.28f)
    }
    val t = appearance.safe().transparency
    val opacity = if (t <= 0.5f) 1f + (a - 1f) * (t * 2f)
        else a + (floor - a) * ((t - 0.5f) * 2f)
    return if (externalBackdrop) maxOf(opacity, 0.90f) else opacity
}

/** The platform effect applies to our background sibling ONLY, never text or child panels. */
fun glassBlurRadius(appearance: GlassAppearance, sdk: Int, ownBackdrop: Boolean = true): Float =
    if (!ownBackdrop || sdk < 31 || appearance.backdrop == GlassBackdrop.SOLID) 0f
    else appearance.safe().frost * 18f

fun ColorScheme.glassified(
    dark: Boolean,
    appearance: GlassAppearance = GlassAppearance(),
    externalBackdrop: Boolean = false
): ColorScheme {
    val base = if (appearance.tint == GlassTint.THEME) glassBaseColor(dark)
        else lerp(glassBaseColor(dark), glassTintColor(appearance.tint, dark), 0.25f)
    fun layer(a: Float) = base.copy(alpha = glassLayerAlpha(a, appearance, externalBackdrop))
    // Unknown desktop content must not show through unpanelled text either.
    val canvas = if (externalBackdrop) base.copy(alpha = 0.90f) else Color.Transparent
    return copy(background = canvas, surface = canvas,
        surfaceContainerLowest = layer(GlassAlphas.LOWEST), surfaceContainerLow = layer(GlassAlphas.LOW),
        surfaceContainer = layer(GlassAlphas.MEDIUM), surfaceContainerHigh = layer(GlassAlphas.HIGH),
        surfaceContainerHighest = layer(GlassAlphas.HIGHEST), surfaceBright = layer(GlassAlphas.MEDIUM),
        surfaceDim = layer(GlassAlphas.LOWEST), surfaceVariant = layer(GlassAlphas.VARIANT),
        surfaceTint = Color.Transparent)
}

fun Modifier.glassBorder(shape: Shape = GlassPanelShape): Modifier = composed {
    val glass = LocalGlassTheme.current
    if (!glass.enabled) this else border(1.dp, glass.borderColor, shape)
}

@Composable
fun Color.glassified(alpha: Float = 0.55f): Color {
    val glass = LocalGlassTheme.current
    return if (glass.enabled) copy(alpha = glassLayerAlpha(alpha, glass.appearance, glass.externalBackdrop)) else this
}

/** Cached brushes, no animation, timer, bitmap capture or background listener. */
@Composable
fun AuroraBackground(
    dark: Boolean, accent: Color, modifier: Modifier = Modifier,
    appearance: GlassAppearance = GlassAppearance()
) {
    val safe = appearance.safe()
    val base = glassTintColor(safe.tint, dark)
    val blobs = auroraBlobColors(dark, accent)
    val radius = glassBlurRadius(safe, Build.VERSION.SDK_INT)
    val effect = if (radius > 0f) Modifier.blur(radius.dp) else Modifier
    Box(modifier.fillMaxSize().then(effect).drawWithCache {
        val w = size.width
        val h = size.height
        // The pre-31 fallback only softens the gradient. It is not a system blur.
        val softness = if (Build.VERSION.SDK_INT < 31) safe.frost else 0f
        val centers = listOf(Offset(w * 0.12f, h * 0.10f), Offset(w * 0.95f, h * 0.22f),
            Offset(w * 0.80f, h * 0.92f), Offset(w * 0.05f, h * 0.78f))
        val radii = listOf(0.85f, 0.70f, 0.75f, 0.60f).map { (w * it * (1f + softness * 0.7f)).coerceAtLeast(1f) }
        val brushes = blobs.mapIndexed { i, color ->
            Brush.radialGradient(listOf(color.copy(alpha = color.alpha * (1f - softness * 0.35f)), Color.Transparent),
                center = centers[i], radius = radii[i])
        }
        val bandBrush = Brush.linearGradient(listOf(blobs[0], Color.Transparent, blobs[2]),
            start = Offset.Zero, end = Offset(w, h))
        onDrawBehind {
            drawRect(base)
            when (safe.backdrop) {
                GlassBackdrop.SOLID -> Unit
                GlassBackdrop.AURORA -> brushes.forEachIndexed { i, brush ->
                    drawCircle(brush, radius = radii[i], center = centers[i])
                }
                GlassBackdrop.BANDS -> {
                    drawRect(bandBrush)
                    // Broad geometric bands make the bounded platform blur visible at their edges.
                    val bandAlpha = (if (dark) 0.08f else 0.12f) * (1f - softness * 0.7f)
                    drawRect(accent.copy(alpha = bandAlpha), topLeft = Offset(w * 0.12f, 0f),
                        size = androidx.compose.ui.geometry.Size(w * 0.22f, h))
                    drawRect(accent.copy(alpha = bandAlpha), topLeft = Offset(w * 0.66f, 0f),
                        size = androidx.compose.ui.geometry.Size(w * 0.12f, h))
                }
            }
        }
    })
}
