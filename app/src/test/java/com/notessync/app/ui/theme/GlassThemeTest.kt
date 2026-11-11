package com.notessync.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.notessync.app.settings.InterfaceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Этап 37: «матовое стекло» — палитра, аврора и кромки панелей. */
class GlassThemeTest {

    @Test
    fun `база стекла различается для светлой и тёмной темы`() {
        assertEquals(Color(0xFFFFFFFF), glassBaseColor(dark = false))
        assertEquals(Color(0xFF14161F), glassBaseColor(dark = true))
    }

    @Test
    fun `стеклянная палитра делает фон прозрачным`() {
        val scheme = lightColorScheme().glassified(dark = false)
        assertEquals(0f, scheme.surface.alpha, 0.001f)
        assertEquals(0f, scheme.background.alpha, 0.001f)
        // Тонирование убрано, чтобы цвета не мутнели.
        assertEquals(0f, scheme.surfaceTint.alpha, 0.001f)
    }

    @Test
    fun `слои стекла плотнеют снизу вверх`() {
        val scheme = darkColorScheme().glassified(dark = true)
        assertTrue(scheme.surfaceContainerLowest.alpha < scheme.surfaceContainerLow.alpha)
        assertTrue(scheme.surfaceContainerLow.alpha < scheme.surfaceContainer.alpha)
        assertTrue(scheme.surfaceContainer.alpha < scheme.surfaceContainerHigh.alpha)
        assertTrue(scheme.surfaceContainerHigh.alpha < scheme.surfaceContainerHighest.alpha)
    }

    @Test
    fun `акцент и цвета текста не ломаются`() {
        val base = lightColorScheme(primary = Color(0xFF0061A4))
        val scheme = base.glassified(dark = false)
        assertEquals(base.primary, scheme.primary)
        assertEquals(base.onSurface, scheme.onSurface)
        assertEquals(base.primaryContainer, scheme.primaryContainer)
    }

    @Test
    fun `панели остаются читаемыми`() {
        // Даже самый лёгкий слой не превращается в невидимку,
        // самый плотный не становится сплошным.
        val scheme = lightColorScheme().glassified(dark = false)
        assertTrue(scheme.surfaceContainerLowest.alpha >= 0.25f)
        assertTrue(scheme.surfaceContainerHighest.alpha <= 0.9f)
    }

    @Test
    fun `аврора строится из акцента и четырёх пятен`() {
        val accent = Color(0xFF6750A4)
        val blobs = auroraBlobColors(dark = false, accent = accent)
        assertEquals(4, blobs.size)
        // Первое пятно — акцент с пониженной прозрачностью.
        assertEquals(accent.red, blobs[0].red, 0.001f)
        assertEquals(accent.green, blobs[0].green, 0.001f)
        assertEquals(accent.blue, blobs[0].blue, 0.001f)
        assertTrue(blobs[0].alpha < 1f)
        // Все пятна полупрозрачные — иначе это не аврора, а обои.
        assertTrue(blobs.all { it.alpha in 0.1f..0.9f })
    }

    @Test
    fun `аврора светлой и тёмной темы различается`() {
        val accent = Color(0xFF6750A4)
        assertFalse(
            auroraBlobColors(dark = false, accent = accent) == auroraBlobColors(dark = true, accent = accent)
        )
        assertFalse(auroraBaseColor(dark = false) == auroraBaseColor(dark = true))
    }

    @Test
    fun `кромка стекла светится и зависит от темы`() {
        val light = glassBorderColor(dark = false)
        val dark = glassBorderColor(dark = true)
        assertTrue(light.alpha > dark.alpha)
        assertTrue(dark.alpha in 0.08f..0.25f)
        assertTrue(light.alpha in 0.5f..0.8f)
    }

    @Test
    fun `по умолчанию интерфейс классический`() {
        assertEquals(InterfaceStyle.CLASSIC, com.notessync.app.settings.SettingsState().interfaceStyle)
        assertEquals("Матовое стекло", InterfaceStyle.GLASS.label)
    }
}
