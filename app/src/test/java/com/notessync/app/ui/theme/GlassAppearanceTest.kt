package com.notessync.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.notessync.app.settings.*
import org.junit.Assert.*
import org.junit.Test

class GlassAppearanceTest {
    @Test fun `invalid values default independently and finite values clamp`() {
        assertEquals(GlassAppearance(), GlassAppearance(Float.NaN, Float.POSITIVE_INFINITY).safe())
        assertEquals(0f, GlassAppearance(-5f).safe().transparency)
        assertEquals(1f, GlassAppearance(frost = 7f).safe().frost)
        assertEquals(GlassTint.ROSE, GlassAppearance(Float.NaN, 0.8f, GlassTint.ROSE).safe().tint)
    }
    @Test fun `transparency extremes preserve midpoint ordering and menu readability`() {
        val layers = listOf(GlassAlphas.LOWEST, GlassAlphas.LOW, GlassAlphas.MEDIUM, GlassAlphas.HIGH, GlassAlphas.HIGHEST)
        for (a in layers) {
            assertEquals(1f, glassLayerAlpha(a, GlassAppearance(0f)), 0.001f)
            assertEquals(a, glassLayerAlpha(a, GlassAppearance()), 0.001f)
            assertTrue(glassLayerAlpha(a, GlassAppearance(1f)) <= a)
        }
        for (t in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            val opacities = layers.map { glassLayerAlpha(it, GlassAppearance(t)) }
            assertEquals(opacities.sorted(), opacities)
            assertTrue(opacities.last() >= 0.82f)
            assertTrue(opacities.first() >= 0.28f)
        }
    }
    @Test fun `true blur is bounded to own non solid background on supported Android`() {
        val full = GlassAppearance(frost = 1f)
        assertEquals(0f, glassBlurRadius(full, 30), 0f)
        assertEquals(18f, glassBlurRadius(full, 31), 0f)
        assertEquals(18f, glassBlurRadius(full.copy(frost = 10f), 35), 0f)
        assertEquals(0f, glassBlurRadius(full, 35, ownBackdrop = false), 0f)
        assertEquals(0f, glassBlurRadius(full.copy(backdrop = GlassBackdrop.SOLID), 35), 0f)
        assertEquals(0f, glassBlurRadius(full.copy(frost = Float.NaN), 35), 0f)
    }
    @Test fun `unknown external background keeps all text surfaces dense`() {
        for (dark in listOf(false, true)) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val scheme = base.glassified(dark, GlassAppearance(1f), externalBackdrop = true)
            assertTrue(scheme.background.alpha >= 0.9f)
            assertTrue(scheme.surface.alpha >= 0.9f)
            assertTrue(scheme.surfaceContainerLowest.alpha >= 0.9f)
            assertEquals(base.onSurface, scheme.onSurface)
        }
    }
    @Test fun `adaptive palettes retain text contrast across backgrounds panels and extremes`() {
        fun contrast(a: Color, b: Color): Float {
            val l1 = a.luminance(); val l2 = b.luminance()
            return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
        }
        for (dark in listOf(false, true)) for (tint in GlassTint.entries) for (t in listOf(0f, 0.5f, 1f)) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val appearance = GlassAppearance(transparency = t, tint = tint)
            val scheme = base.glassified(dark, appearance)
            val background = glassTintColor(tint, dark)
            // Include individual peaks and conservative all-peaks overlap, stronger than actual geometry.
            val blobs = auroraBlobColors(dark, base.primary)
            val samples = listOf(background) + blobs.map { it.compositeOver(background) } +
                listOf(blobs.fold(background) { under, blob -> blob.compositeOver(under) })
            for (sample in samples) for (surface in listOf(Color.Transparent, scheme.surfaceContainerLowest,
                scheme.surfaceContainer, scheme.surfaceContainerHighest)) {
                val panel = surface.compositeOver(sample)
                for (text in listOf(base.onSurface, base.onSurfaceVariant)) {
                    assertTrue("dark=$dark tint=$tint t=$t contrast=${contrast(text, panel)}", contrast(text, panel) >= 4.5f)
                }
            }
        }
    }
}
