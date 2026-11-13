package com.notessync.app.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.ui.screens.SettingsScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsGroupsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    @Before fun before() {
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setThemeMode(ThemeMode.LIGHT)
            setFontScale(SettingsRepository.FONT_NORMAL); setEditorHistory(true); setAudioRecording(true)
            setBackupFrequency(BackupFrequency.MANUAL); setAiEnabled(false) }
    }
    @After fun after() {
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setThemeMode(ThemeMode.SYSTEM)
            setFontScale(SettingsRepository.FONT_NORMAL); setEditorHistory(true); setAudioRecording(true) }
    }
    private fun group(id: String) = compose.onNodeWithTag("settings-group-$id")
    private fun open(id: String) { group(id).performScrollTo().performClick() }
    @Test fun `only open settings compose and labelled rows toggle real preferences`() {
        compose.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { SettingsScreen({}, {}) } } }
        compose.onNodeWithText("Русский").assertIsDisplayed()
        compose.onNodeWithText("История изменений").assertDoesNotExist()
        compose.onNodeWithText("API-ключ не настроен").assertDoesNotExist()
        open("editor")
        group("editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развёрнуто"))
        compose.onNodeWithText("История изменений").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(settings.state.value.editorHistory)
        open("voice")
        compose.onNodeWithTag("settings-content-editor").assertDoesNotExist()
        compose.onNodeWithText("Запись аудио в заметку").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(settings.state.value.audioRecording)
        open("editor")
        compose.onNodeWithText("История изменений").performScrollTo().assertIsOff()
    }
    @Test fun `English dark narrow settings keep backup and sync actions reachable at large font`() {
        settings.apply { setUiLanguage(UiLanguage.ENGLISH); setThemeMode(ThemeMode.DARK); setFontScale(SettingsRepository.FONT_LARGE) }
        val opens = AtomicInteger()
        compose.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { SettingsScreen({}, { opens.incrementAndGet() }) } } }
        open("backup")
        compose.onNodeWithText("Save a backup").performScrollTo().assertIsDisplayed()
        open("sync")
        compose.onNodeWithTag("settings-content-backup").assertDoesNotExist()
        compose.onNodeWithText("Wi-Fi sync").performScrollTo().performClick()
        assertEquals(1, opens.get())
        group("voice").performScrollTo().assert(hasText("Voice and audio"))
    }
    @Test fun `open group survives recreation and Back collapses before leaving settings`() {
        val back = AtomicInteger()
        val restore = StateRestorationTester(compose)
        restore.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { SettingsScreen({ back.incrementAndGet() }, {}) } } }
        open("editor")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings-content-editor").assertExists()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("settings-content-editor").assertDoesNotExist()
        assertEquals(0, back.get())
        compose.onNodeWithContentDescription("Назад").performClick()
        assertEquals(1, back.get())
    }
}
