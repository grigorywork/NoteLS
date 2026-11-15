package com.notessync.app.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.notessync.app.ui.settings.BackupSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
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
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings get() = SettingsRepository.get(RuntimeEnvironment.getApplication())
    private lateinit var drawingView: View
    private var density = 1f
    @Before fun before() {
        settings.apply {
            setUiLanguage(UiLanguage.RUSSIAN); setThemeMode(ThemeMode.LIGHT); setFontScale(SettingsRepository.FONT_NORMAL)
            setAppFont(AppFont.SYSTEM); setInterfaceStyle(InterfaceStyle.CLASSIC); setBackupFrequency(BackupFrequency.MANUAL)
            setVoiceEngine(VoiceEngine.ONLINE); setVoiceLanguage(VoiceLanguage.SYSTEM); setVoiceControl(VoiceControl.HOLD)
            setVoiceStructure(VoiceStructure.POINTS); setAiEnabled(false)
        }
    }
    @After fun after() {
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setAppFont(AppFont.SYSTEM); setThemeMode(ThemeMode.SYSTEM) }
    }
    private fun content() { compose.setContent {
        drawingView = LocalView.current; density = LocalDensity.current.density
        NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 640.dp).testTag("settings-viewport")) { SettingsScreen({}, {}) } }
    } }
    private fun open(id: String) = compose.onNodeWithTag("settings-group-$id").performScrollTo().performClick()
    private fun choice(id: String) = compose.onNodeWithTag("settings-choice-$id")
    private fun screenshot(name: String) {
        val bounds = compose.onNodeWithTag("settings-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top); drawingView.draw(canvas)
            val output = File("build/reports/settings-preview/$name.png").apply { parentFile?.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun `all seven sections fit a narrow compact viewport with accessible touch targets`() {
        content()
        val viewport = compose.onNodeWithTag("settings-viewport").fetchSemanticsNode().boundsInRoot
        for (id in listOf("appearance", "editor", "voice", "transform", "sync", "backup", "about")) {
            val bounds = compose.onNodeWithTag("settings-group-$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(id, bounds.height >= 48 * density - 1f)
            assertTrue(id, bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        compose.onNodeWithText("API-ключ не настроен").assertDoesNotExist()
        screenshot("overview-320")
    }
    @Test fun `font menu retains every option selected state persistence and collapsed help`() {
        content(); open("appearance")
        compose.onNodeWithText("Montserrat").assertDoesNotExist()
        compose.onNodeWithText("Лицензии шрифтов").assertDoesNotExist()
        choice("font").performScrollTo().performClick()
        compose.onNodeWithText("Montserrat").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AppFont.MONTSERRAT, settings.state.value.appFont) }
        choice("font").performScrollTo().performClick()
        compose.onNode(hasText("Montserrat") and hasAnyAncestor(isPopup())).assertIsSelected()
        compose.onNode(isPopup()).performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("settings-help-fonts").performScrollTo().performClick()
        compose.onNodeWithText("Лицензии шрифтов").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-help-fonts").performScrollTo().performClick()
        compose.onNodeWithText("Лицензии шрифтов").assertDoesNotExist()
        screenshot("appearance-320")
    }
    @Test fun `compact voice choices update real preferences and retain local privacy help`() {
        content(); open("voice")
        choice("voice-engine").performScrollTo().performClick(); compose.onNodeWithText("На устройстве").performClick()
        choice("voice-language").performScrollTo().performClick(); compose.onNode(hasText("Русский") and hasAnyAncestor(isPopup())).performClick()
        choice("voice-control").performScrollTo().performClick(); compose.onNodeWithText(VoiceControl.TAP.label).performClick()
        choice("voice-structure").performScrollTo().performClick(); compose.onNodeWithText("Как есть").performClick()
        compose.runOnIdle {
            val s = settings.state.value
            assertEquals(VoiceEngine.LOCAL, s.voiceEngine); assertEquals(VoiceLanguage.RUSSIAN, s.voiceLanguage)
            assertEquals(VoiceControl.TAP, s.voiceControl); assertEquals(VoiceStructure.RAW, s.voiceStructure)
        }
        compose.onNodeWithText("Локально: Android 12+ и модель на устройстве. Без перехода в онлайн.").performScrollTo().assertIsDisplayed()
        screenshot("voice-320")
        compose.onNodeWithTag("settings-help-voice").performScrollTo().performClick()
        compose.onNodeWithText("Онлайн-режим использует системный сервис: звук может передаваться его провайдеру. Локальный режим требует Android 12+ и доступную модель на устройстве; автоматического перехода в онлайн нет.")
            .performScrollTo().assertIsDisplayed()
    }
    @Test fun `grouped editor controls retain all feature switches and completed style`() {
        content(); open("editor")
        val switches: List<Pair<String, (SettingsState) -> Boolean>> = listOf(
            "Выполненные — в отдельный раздел" to { it.archiveCompletedTasks },
            "Сразу выбирать время быстрой задачи" to { it.quickTaskTime },
            "Флажки приоритета" to { it.showTaskPriorities },
            "История изменений" to { it.editorHistory },
            "Расширенное оформление" to { it.advancedFormatting },
            "Показывать таблицы" to { it.showTables },
            "Камера в редакторе" to { it.cameraCapture },
            "Размеры фото" to { it.photoResize })
        for ((label, read) in switches) {
            val before = read(settings.state.value)
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(label, !before, read(settings.state.value)) }
        }
        choice("completed").performScrollTo().performClick(); compose.onNodeWithText("Скрывать").performClick()
        compose.runOnIdle { assertEquals(CompletedStyle.HIDE, settings.state.value.completedStyle) }
        compose.onNodeWithContentDescription("Назад").performClick(); open("editor")
        compose.onNodeWithText("Выполненные — в отдельный раздел").performScrollTo()
            .assert(if (settings.state.value.archiveCompletedTasks) isOn() else isOff())
    }
    @Test fun `backup frequency retains its warning and busy state dismisses menus without reopening`() {
        var busy by mutableStateOf(false)
        compose.setContent {
            val state by settings.state.collectAsState()
            NoteSyncTheme { Column(Modifier.requiredSize(320.dp, 640.dp).verticalScroll(rememberScrollState())) {
                BackupSettings(state, settings, busy, 0, {}, {}, {}, configured = false)
            } }
        }
        choice("backup-frequency").performScrollTo().performClick()
        compose.onNodeWithText(BackupFrequency.DAILY.label).performClick()
        compose.runOnIdle { assertEquals(BackupFrequency.DAILY, settings.state.value.backupFrequency) }
        choice("backup-frequency").performScrollTo().performClick()
        compose.onNode(isPopup()).assertExists()
        compose.runOnIdle { busy = true }
        compose.onNode(isPopup()).assertDoesNotExist()
        choice("backup-frequency").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNode(isPopup()).assertDoesNotExist()
        choice("backup-frequency").assertIsEnabled()
        compose.onNodeWithText("локальные копии тоже удаляются", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(BackupFrequency.DAILY, settings.state.value.backupFrequency) }
    }
}
