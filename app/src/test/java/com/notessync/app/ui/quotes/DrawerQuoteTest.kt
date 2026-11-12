package com.notessync.app.ui.quotes

import android.app.Application
import com.notessync.app.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DrawerQuoteTest {
    @Test fun `settings save all quote appearance values and preserve custom text`() {
        val repo = SettingsRepository.get(RuntimeEnvironment.getApplication()); val original = repo.state.value.drawerQuote
        try {
            val style = DrawerQuoteAppearance(false, "Строка\nещё", AppFont.MONO, 24f, 0xFFFFFFFF, 0xFF202124)
            repo.setDrawerQuoteAppearance(style); assertEquals(style, repo.state.value.drawerQuote)
            assertEquals(style.customText, drawerQuoteText(style.customText, 1))
        } finally { repo.setDrawerQuoteAppearance(original) }
    }
    @Test fun `random quotes include attribution and invalid indices are safe`() {
        assertEquals(QuotesData.formatted(QuotesData.quotes[1]), drawerQuoteText(" ", 1))
        assertEquals(QuotesData.formatted(QuotesData.quotes.first()), drawerQuoteText("", -1))
    }
    @Test fun `invalid appearance is clamped and theme fallback retained`() {
        val safe = DrawerQuoteAppearance(customText = "x".repeat(4000), size = Float.NaN, textColor = 1, backgroundColor = -1).safe()
        assertEquals(3000, safe.customText.length); assertEquals(16f, safe.size); assertEquals(0L, safe.textColor); assertEquals(0L, safe.backgroundColor)
        assertEquals(12f, DrawerQuoteAppearance(size = -1f).safe().size); assertEquals(32f, DrawerQuoteAppearance(size = 99f).safe().size)
    }
}
