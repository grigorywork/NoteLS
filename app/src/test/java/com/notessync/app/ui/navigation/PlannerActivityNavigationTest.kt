package com.notessync.app.ui.navigation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.launcher.PlannerShortcutLinks
import com.notessync.app.settings.*
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlannerActivityNavigationTest {
    private val note = NoteEntity(id = 1, title = "Planner draft", content = "Original")
    private val fixture = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication(); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
            SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL) }
            runBlocking { AppDatabase.get(app).noteDao().upsert(note) }
        }
        override fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    }
    private val intent = PlannerShortcutLinks.intent(RuntimeEnvironment.getApplication())
    val compose = AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(intent)) { rule ->
        lateinit var activity: MainActivity; rule.scenario.onActivity { activity = it }; activity
    }
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun await(matcher: SemanticsMatcher) { compose.waitUntil(15_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100)); compose.waitForIdle(); compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    } }
    private fun launchAgain() { compose.runOnIdle {
        InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, intent)
    } }
    @Test fun `cold repeated and restored planner launches retain a single calendar with normal Back navigation`() {
        await(hasContentDescription("Предыдущий месяц")); compose.onNodeWithText("Календарь").assertIsDisplayed()
        launchAgain(); await(hasContentDescription("Предыдущий месяц"))
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle(); compose.activityRule.scenario.recreate()
        await(hasContentDescription("Предыдущий месяц"))
        compose.onNodeWithContentDescription("Назад").performClick(); await(hasContentDescription("Поиск заметок"))
    }
    @Test fun `warm planner launch saves pending editor text before navigating and Back restores the editor`() {
        await(hasContentDescription("Предыдущий месяц")); compose.onNodeWithContentDescription("Назад").performClick()
        await(hasText(note.title)); compose.onNodeWithText(note.title).performClick(); await(hasText("Original") and hasSetTextAction())
        compose.onNode(hasText("Original") and hasSetTextAction()).performTextReplacement("Unsaved before planner")
        launchAgain(); await(hasContentDescription("Предыдущий месяц"))
        assertEquals("Unsaved before planner", runBlocking { AppDatabase.get(RuntimeEnvironment.getApplication()).noteDao().getById(note.id)!!.content })
        compose.onNodeWithContentDescription("Назад").performClick(); await(hasText("Unsaved before planner") and hasSetTextAction())
        compose.onNodeWithContentDescription("Назад").performClick(); await(hasContentDescription("Поиск заметок"))
    }
}
