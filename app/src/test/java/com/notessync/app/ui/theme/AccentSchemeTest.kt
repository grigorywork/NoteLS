package com.notessync.app.ui.theme

import androidx.compose.ui.graphics.luminance
import com.notessync.app.settings.AccentColor
import org.junit.Assert.*
import org.junit.Test

class AccentSchemeTest {
    @Test fun `all explicit accents cover secondary and tertiary buttons with readable contrast`() {
        for (accent in AccentColor.entries.filter { it != AccentColor.DYNAMIC }) for (dark in listOf(false, true)) {
            val colors = accentScheme(accent, dark)!!
            assertEquals(colors.primary, colors.secondary); assertEquals(colors.primary, colors.tertiary)
            assertEquals(colors.primaryContainer, colors.secondaryContainer)
            val contrast = (maxOf(colors.primary.luminance(), colors.onPrimary.luminance()) + 0.05f) /
                (minOf(colors.primary.luminance(), colors.onPrimary.luminance()) + 0.05f)
            assertTrue("$accent dark=$dark contrast=$contrast", contrast >= 4.5f)
        }
    }
}
