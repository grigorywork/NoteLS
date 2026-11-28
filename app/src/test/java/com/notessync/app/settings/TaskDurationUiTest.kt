package com.notessync.app.settings

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
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.ui.analytics.AnalyticsViewModel
import com.notessync.app.ui.screens.AnalyticsScreen
import com.notessync.app.ui.settings.EditorSettings
import com.notessync.app.ui.tasks.CountdownIndicator
import com.notessync.app.ui.tasks.TaskTimerIndicator
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskDurationUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repository get() = SettingsRepository.get(app)
    private lateinit var drawingView: View
    private val models = ViewModelStore()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        repository.apply { setUiLanguage(UiLanguage.RUSSIAN); setFontScale(1f); setAutomaticDurationUnits(true) }
    }
    @After fun after() { models.clear(); compose.waitForIdle(); AppDatabase.closeInstance(); repository.apply { setUiLanguage(UiLanguage.RUSSIAN); setFontScale(1f); setAutomaticDurationUnits(true) } }
    private fun await(check: () -> Boolean) { compose.waitUntil(15_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100)); compose.waitForIdle(); check()
    } }
    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun preview() { compose.setContent {
        drawingView = LocalView.current
        val state by repository.state.collectAsState()
        NoteSyncTheme(darkTheme = false, dynamicColor = false, fontScale = state.fontScale) {
            Column(Modifier.requiredSize(320.dp, 640.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                TaskTimerIndicator(3_723_000L, null, compact = true)
                CountdownIndicator(86_400_000L, null, compact = true, animateIcon = false)
                EditorSettings(state, repository)
            }
        }
    } }
    private fun screenshot(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(drawingView.width, drawingView.height, Bitmap.Config.ARGB_8888)
        drawingView.draw(Canvas(bitmap)); File("build/reports/duration-preview/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun `settings toggle updates both paused clocks and preserves full accessibility precision`() {
        preview(); await { shown("1ч02м") && shown("1д00ч00м") }
        compose.onNodeWithContentDescription("1ч 02м 03с").assertExists()
        compose.onNodeWithText("Часы и дни в таймерах").performScrollTo().performClick()
        await { shown("62:03") && shown("1440:00") }
        assertFalse(repository.state.value.automaticDurationUnits)
        compose.onNodeWithText("Часы и дни в таймерах").performClick()
        await { shown("1ч02м") && shown("1д00ч00м") }
        compose.onNodeWithText("Часы и дни в таймерах").assertIsDisplayed(); screenshot("settings-ru-320")
    }
    @Test fun `English large font keeps the switch reachable and uses English units`() {
        repository.setUiLanguage(UiLanguage.ENGLISH); repository.setFontScale(SettingsRepository.FONT_LARGE)
        preview(); await { shown("1h02m") && shown("1d00h00m") }
        compose.onNodeWithText("Hours and days in timers").performScrollTo().performClick()
        await { shown("62:03") && shown("1440:00") }
        compose.onNodeWithText("Hours and days in timers").assertIsDisplayed()
        screenshot("settings-en-large-320")
    }
    @Test fun `analytics follows the same setting without writing task timestamps or elapsed time`() {
        val db = AppDatabase.get(app)
        val source = NoteEntity(title = "Duration", isTask = true, isCompleted = true,
            taskCompletedAt = System.currentTimeMillis() - 1000, taskElapsedMillis = 86_400_000L)
        val saved = runBlocking { source.copy(id = db.noteDao().upsert(source)) }
        val vm = AnalyticsViewModel(app).also { models.put("analytics", it) }
        compose.setContent { drawingView = LocalView.current; NoteSyncTheme(darkTheme = false, dynamicColor = false) {
            Box(Modifier.requiredSize(320.dp, 760.dp)) { AnalyticsScreen({}, {}, vm) }
        } }
        await { shown("Всего: 1д 00ч 00м") }
        compose.onNodeWithText("Всего: 1д 00ч 00м").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { repository.setAutomaticDurationUnits(false) }
        await { shown("Всего: 1440:00") }
        compose.onNodeWithText("Всего: 1440:00").assertIsDisplayed(); screenshot("analytics-minutes-320")
        assertEquals(saved, runBlocking { db.noteDao().getById(saved.id) })
    }
}
