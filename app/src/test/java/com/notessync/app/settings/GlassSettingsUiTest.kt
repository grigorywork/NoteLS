package com.notessync.app.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.ui.screens.SettingsScreen
import com.notessync.app.ui.settings.GlassSettingsDialog
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings get() = SettingsRepository.get(RuntimeEnvironment.getApplication())
    @Before fun before() {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        settings.apply {
        setUiLanguage(UiLanguage.RUSSIAN); setThemeMode(ThemeMode.LIGHT); setFontScale(1f)
        setGlassAppearance(GlassAppearance()); setInterfaceStyle(InterfaceStyle.GLASS)
    } }
    @After fun after() { settings.apply {
        setUiLanguage(UiLanguage.RUSSIAN); setThemeMode(ThemeMode.SYSTEM); setFontScale(1f)
        setGlassAppearance(GlassAppearance()); setInterfaceStyle(InterfaceStyle.CLASSIC)
    } }
    private fun dialog() { compose.setContent {
        val state by settings.state.collectAsState()
        var visible by remember { mutableStateOf(true) }
        NoteSyncTheme(darkTheme = state.themeMode == ThemeMode.DARK, dynamicColor = false,
            interfaceStyle = state.interfaceStyle, glassAppearance = state.glassAppearance) {
            Box(Modifier.requiredSize(320.dp, 640.dp)) {
                if (visible) GlassSettingsDialog(state, { visible = false }, settings::setGlassAppearance)
            }
        }
    } }
    private fun progress(id: String, value: Float) = compose.onNodeWithTag("glass-$id").performScrollTo()
        .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun select(id: String, label: String) {
        compose.onNodeWithTag("settings-choice-glass-$id").performScrollTo().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }
    private fun screenshot(name: String) {
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle {
            // NativeCanvas draws the actual dialog view. PixelCopy's redraw callback is not simulated.
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/glass-preview/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun `draft preview does not persist until Save and solid disables only frost`() {
        dialog(); progress("transparency", 0.9f); progress("frost", 0.7f)
        select("tint", "Бирюзовый"); select("backdrop", "Однотонный")
        compose.onNodeWithTag("glass-frost").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(GlassAppearance(), settings.state.value.glassAppearance) }
        compose.onNodeWithTag("glass-preview").performScrollTo().assertIsDisplayed(); screenshot("light-draft-320")
        compose.onNodeWithTag("glass-save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(GlassAppearance(0.9f, 0.7f, GlassTint.TEAL, GlassBackdrop.SOLID), settings.state.value.glassAppearance) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
    @Test fun `cancel discards draft and reset does not persist without Save`() {
        val original = GlassAppearance(0.2f, 0.8f, GlassTint.AMBER, GlassBackdrop.BANDS)
        settings.setGlassAppearance(original)
        dialog(); compose.onNodeWithTag("glass-reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original, settings.state.value.glassAppearance) }
        compose.onNodeWithTag("glass-cancel").performClick()
        compose.runOnIdle { assertEquals(original, settings.state.value.glassAppearance) }
    }
    @Test @Config(qualifiers = "ru-w320dp-h640dp")
    fun `English dark large text keeps actions help and draft on restoration`() {
        settings.apply { setUiLanguage(UiLanguage.ENGLISH); setThemeMode(ThemeMode.DARK); setFontScale(SettingsRepository.FONT_LARGE) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by settings.state.collectAsState()
            NoteSyncTheme(darkTheme = true, dynamicColor = false, fontScale = state.fontScale) {
                GlassSettingsDialog(state, {}, settings::setGlassAppearance)
            }
        }
        progress("transparency", 0.8f); select("tint", "Lilac"); select("backdrop", "Bands")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings-choice-glass-tint").performScrollTo().assert(hasText("Lilac"))
        compose.onNodeWithTag("settings-help-glass-rendering").performScrollTo().performClick()
        compose.onNodeWithText("Android 12+:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("glass-save").assertIsDisplayed()
        compose.onNodeWithTag("glass-preview").performScrollTo(); screenshot("dark-large-320")
        compose.onNodeWithTag("glass-save").performClick()
        compose.runOnIdle { assertEquals(GlassAppearance(0.8f, 0f, GlassTint.LILAC, GlassBackdrop.BANDS), settings.state.value.glassAppearance) }
    }
    @Test fun `actual appearance entry is conditional and Back discards its draft`() {
        settings.setInterfaceStyle(InterfaceStyle.CLASSIC)
        compose.setContent {
            val state by settings.state.collectAsState()
            NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 640.dp).testTag("glass-settings-host")) { SettingsScreen({}, {}) } }
        }
        compose.onNodeWithTag("settings-group-appearance").performScrollTo().performClick()
        compose.onNodeWithTag("settings-glass-open").assertDoesNotExist()
        compose.runOnIdle { settings.setInterfaceStyle(InterfaceStyle.GLASS) }
        compose.onNodeWithTag("settings-glass-open").performScrollTo().performClick()
        progress("transparency", 1f)
        // Exercise Android's real dialog Back dispatcher, rather than a key sent to the Compose child.
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(GlassAppearance(), settings.state.value.glassAppearance) }
        compose.onNodeWithTag("settings-glass-open").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "en-w320dp-h640dp")
    fun `historical style chips and glass configuration remain accessible with large English text`() {
        settings.apply { setUiLanguage(UiLanguage.ENGLISH); setThemeMode(ThemeMode.DARK)
            setFontScale(SettingsRepository.FONT_LARGE); setAppFont(AppFont.SYSTEM) }
        lateinit var view: android.view.View
        var density = 1f
        compose.setContent {
            val state by settings.state.collectAsState()
            view = androidx.compose.ui.platform.LocalView.current
            density = androidx.compose.ui.platform.LocalDensity.current.density
            NoteSyncTheme(darkTheme = true, dynamicColor = false, fontScale = state.fontScale,
                interfaceStyle = InterfaceStyle.GLASS, glassAppearance = state.glassAppearance) {
                Box(Modifier.requiredSize(320.dp, 640.dp).testTag("historical-settings-host")) { SettingsScreen({}, {}) }
            }
        }
        compose.onNodeWithTag("settings-group-appearance").performScrollTo().performClick()
        compose.onNodeWithText(InterfaceStyle.CLASSIC.label).assertExists()
        compose.onNodeWithText(InterfaceStyle.GLASS.label).performScrollTo().assertIsSelected()
        val configure = compose.onNodeWithTag("settings-glass-open").performScrollTo().assertIsDisplayed()
        val button = configure.fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithTag("historical-settings-host").fetchSemanticsNode().boundsInRoot
        assertTrue("Touch target", button.height >= 48f * density - 1f)
        assertTrue("Within narrow viewport", button.left >= bounds.left && button.right <= bounds.right)
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top); view.draw(canvas)
            val file = File("build/reports/settings-preview/appearance-chips-glass-large-en-320.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        configure.performClick(); progress("transparency", 0.8f)
        compose.onNodeWithTag("glass-cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(GlassAppearance(), settings.state.value.glassAppearance) }
    }

}
