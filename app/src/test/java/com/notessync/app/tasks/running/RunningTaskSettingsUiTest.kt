package com.notessync.app.tasks.running

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
import com.notessync.app.settings.SettingsState
import com.notessync.app.ui.settings.RunningTaskSettings
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
class RunningTaskSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    private lateinit var view: View
    @Before fun before() {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        settings.setRunningTaskNotifications(true); settings.setUiLanguage(UiLanguage.RUSSIAN); settings.setFontScale(1f)
    }
    @After fun after() { settings.setRunningTaskNotifications(true); settings.setUiLanguage(UiLanguage.RUSSIAN); settings.setFontScale(1f); RunningTaskNotifications.stop() }
    private fun preview() = compose.setContent {
        view = LocalView.current
        val state by settings.state.collectAsState()
        NoteSyncTheme(darkTheme = false, dynamicColor = false, fontScale = state.fontScale) {
            Column(Modifier.requiredSize(320.dp, 640.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                RunningTaskSettings(state, settings)
            }
        }
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        File("build/reports/running-task-preview/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun `notification switch and collapsible explanation are reachable without changing task data`() {
        preview(); compose.onNodeWithText("Идущая задача в шторке").performClick()
        assertFalse(settings.state.value.runningTaskNotifications)
        compose.onNodeWithText("Идущая задача в шторке").performClick(); assertTrue(settings.state.value.runningTaskNotifications)
        compose.onNodeWithTag("settings-help-running-task-notifications").performClick()
        compose.onNodeWithText("Android ведёт счётчик", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("settings-ru-320")
    }
    @Test fun `English large font keeps the system-counter explanation and switch accessible`() {
        settings.setUiLanguage(UiLanguage.ENGLISH); settings.setFontScale(1.15f)
        preview(); compose.onNodeWithText("Running task in notifications").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-help-running-task-notifications").performScrollTo().performClick()
        compose.onNodeWithText("Android runs the notification counter", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("settings-en-large-320")
        compose.onNodeWithText("Running task in notifications").performScrollTo().performClick()
        assertFalse(settings.state.value.runningTaskNotifications)
    }
}
