package com.notessync.app.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.settings.LauncherIconSettings
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
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var view: View
    private lateinit var restoration: StateRestorationTester
    @After fun cleanup() { LauncherIcons(app).select(LauncherIcon.GLASS); SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setFontScale(SettingsRepository.FONT_NORMAL) } }
    private fun content(english: Boolean = false) {
        SettingsRepository.get(app).apply { setUiLanguage(if (english) UiLanguage.ENGLISH else UiLanguage.RUSSIAN)
            setFontScale(if (english) SettingsRepository.FONT_LARGE else SettingsRepository.FONT_NORMAL) }
        restoration = StateRestorationTester(compose)
        restoration.setContent { NoteSyncTheme { val currentView = LocalView.current; SideEffect { view = currentView }
            Box(Modifier.requiredSize(320.dp, 600.dp).testTag("icon-viewport")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { LauncherIconSettings() }
            }
        } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-icon-busy").fetchSemanticsNodes().isEmpty() }
    }
    private fun choice(icon: LauncherIcon) = compose.onNodeWithTag("launcher-icon-${icon.name.lowercase()}")
    @Test fun `three previews select actual launcher and show selected state on narrow screen`() {
        content()
        for (target in listOf(LauncherIcon.PAPER, LauncherIcon.FROSTED, LauncherIcon.GLASS)) {
            choice(target).performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-icon-busy").fetchSemanticsNodes().isEmpty() }
            choice(target).assertIsSelected()
            assertEquals(target, LauncherIcons(app).current())
        }
        screenshot("launcher-icons-ru-320")
    }
    @Test fun `two clicks before recomposition keep the first pending choice and recreation restores it`() {
        content()
        val paper = choice(LauncherIcon.PAPER).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val frosted = choice(LauncherIcon.FROSTED).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { paper(); frosted() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-icon-busy").fetchSemanticsNodes().isEmpty() }
        choice(LauncherIcon.PAPER).assertIsSelected()
        choice(LauncherIcon.FROSTED).assertIsNotSelected()
        assertEquals(LauncherIcon.PAPER, LauncherIcons(app).current())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-icon-busy").fetchSemanticsNodes().isEmpty() }
        choice(LauncherIcon.PAPER).assertIsSelected()
        assertEquals(LauncherIcon.PAPER, LauncherIcons(app).current())
        screenshot("launcher-icons-paper-restored-320")
    }
    @Test fun `English large text labels and choices stay reachable`() {
        content(english = true)
        compose.onNodeWithText("App icon").assertIsDisplayed()
        for (label in listOf("Pink glass", "Frosted", "Folded note")) compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        screenshot("launcher-icons-en-large-320")
    }
    private fun screenshot(name: String) {
        val box = compose.onNodeWithTag("icon-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(box.width.toInt(), box.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-box.left, -box.top); view.draw(canvas)
            File("build/reports/settings-preview/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
