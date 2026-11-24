package com.notessync.app.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.settings.PlannerShortcutSettings
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlannerShortcutUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings get() = SettingsRepository.get(RuntimeEnvironment.getApplication())
    private lateinit var view: View
    private class Platform(val supported: Boolean, var accepted: Boolean = true) : PlannerShortcutController {
        var requests = 0
        override fun isSupported() = supported
        override fun request(): Boolean { requests++; return accepted }
    }
    @After fun after() { settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setFontScale(SettingsRepository.FONT_NORMAL) } }
    private fun content(platform: Platform, english: Boolean = false) {
        settings.apply { setUiLanguage(if (english) UiLanguage.ENGLISH else UiLanguage.RUSSIAN)
            setFontScale(if (english) SettingsRepository.FONT_LARGE else SettingsRepository.FONT_NORMAL) }
        compose.setContent { NoteSyncTheme { val current = LocalView.current; SideEffect { view = current }
            Box(Modifier.requiredSize(320.dp, 600.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { PlannerShortcutSettings(platform) }
            }
        } }
    }
    @Test fun `only an explicit tap requests pin and feedback distinguishes acceptance from rejection`() {
        val platform = Platform(true); content(platform)
        compose.runOnIdle { assertEquals(0, platform.requests) }
        compose.onNodeWithText("Добавить на главный экран").assertIsDisplayed().performClick()
        compose.onNodeWithText("Запрос на добавление ярлыка отправлен.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, platform.requests); platform.accepted = false }
        compose.onNodeWithText("Добавить на главный экран").performClick()
        compose.onNodeWithText("Не удалось запросить ярлык. Попробуй ещё раз.").assertIsDisplayed()
        compose.onNodeWithText("Запрос на добавление ярлыка отправлен.").assertDoesNotExist()
        screenshot("planner-shortcut-320")
    }
    @Test fun `unsupported launcher has clear English fallback and disabled action at large font`() {
        val platform = Platform(false); content(platform, english = true)
        compose.onNodeWithText("Add to home screen").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Your launcher does not support adding a shortcut from the app.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, platform.requests) }
    }
    private fun screenshot(name: String) { compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(320, 600, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        File("build/reports/settings-preview/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    } }
}
