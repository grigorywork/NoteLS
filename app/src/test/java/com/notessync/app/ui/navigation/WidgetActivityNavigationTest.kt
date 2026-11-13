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
import com.notessync.app.settings.*
import com.notessync.app.security.ProtectionSession
import com.notessync.app.widget.WidgetNoteLinks
import kotlinx.coroutines.runBlocking
import org.junit.*
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
class WidgetActivityNavigationTest {
    private val note = NoteEntity(id = 1, syncId = "widget-start-test", title = "Exact widget note", content = "Original")
    private val fixture = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
            SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL) }
            runBlocking { AppDatabase.get(app).noteDao().upsert(note) }
        }
        override fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    }
    // ActivityScenario follows lifecycle events only for its original Intent.filterEquals.
    // Launch with the real widget intent so setIntent(onNewIntent) remains observable at recreation.
    private val intent = WidgetNoteLinks.intent(RuntimeEnvironment.getApplication(), 21, note.id, note.syncId)
    val compose = AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(intent)) { rule ->
        lateinit var activity: MainActivity
        rule.scenario.onActivity { activity = it }
        activity
    }
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(350)); compose.waitForIdle()
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Test fun `cold widget launch and repeated intent open exact note while recreation restores just one editor`() {
        await(hasText(note.title) and hasSetTextAction())
        compose.onNodeWithContentDescription("Назад").performClick(); await(hasContentDescription("Поиск заметок"))
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, intent) }
        await(hasText(note.title) and hasSetTextAction())
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        await(hasText(note.title) and hasSetTextAction())
        compose.onNodeWithContentDescription("Назад").performClick(); await(hasContentDescription("Поиск заметок"))
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        Assert.assertEquals(note, runBlocking { AppDatabase.get(RuntimeEnvironment.getApplication()).noteDao().getById(note.id) })
    }
}
