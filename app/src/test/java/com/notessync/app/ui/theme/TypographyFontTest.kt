package com.notessync.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import com.notessync.app.settings.AppFont
import org.junit.Assert.assertEquals
import org.junit.Test

/** Этап 34: выбор системного шрифта применяется ко всей типографике. */
class TypographyFontTest {

    @Test
    fun `стандартный шрифт без масштаба возвращает базовую типографику`() {
        assertEquals(Typography, scaledTypography(1.0f, AppFont.SYSTEM))
    }

    @Test
    fun `семейство применяется ко всем стилям`() {
        val typography = scaledTypography(1.0f, AppFont.SERIF)
        assertEquals(FontFamily.Serif, typography.bodyLarge.fontFamily)
        assertEquals(FontFamily.Serif, typography.titleLarge.fontFamily)
        assertEquals(FontFamily.Serif, typography.labelSmall.fontFamily)
        assertEquals(FontFamily.Serif, typography.displayLarge.fontFamily)
    }

    @Test
    fun `все варианты шрифтов маппятся`() {
        assertEquals(FontFamily.Default, appFontFamily(AppFont.SYSTEM))
        assertEquals(FontFamily.SansSerif, appFontFamily(AppFont.SANS))
        assertEquals(FontFamily.Serif, appFontFamily(AppFont.SERIF))
        assertEquals(FontFamily.Monospace, appFontFamily(AppFont.MONO))
        assertEquals(FontFamily.Cursive, appFontFamily(AppFont.CURSIVE))
    }

    @Test
    fun `масштаб и шрифт работают вместе`() {
        val typography = scaledTypography(1.15f, AppFont.MONO)
        assertEquals(FontFamily.Monospace, typography.bodyMedium.fontFamily)
        assertEquals(
            Typography.bodyMedium.fontSize * 1.15f,
            typography.bodyMedium.fontSize
        )
    }
}
