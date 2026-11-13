package com.notessync.app.ui.navigation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class MainActivityLaunchTest {
    private val fixture = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
            SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL) }
        }
        override fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    }
    val compose = createAndroidComposeRule<MainActivity>()
    private val diagnostics = object : org.junit.rules.TestWatcher() {
        override fun failed(error: Throwable, description: org.junit.runner.Description) {
            System.err.println("Navigation test body failure: " + error.stackTraceToString())
        }
    }
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixture).around(compose).around(diagnostics)
    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(350)); compose.waitForIdle()
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Test fun `real MainActivity launches navigation through the localized context in both UI languages`() {
        await(hasContentDescription("Поиск заметок"))
        compose.onNodeWithContentDescription("Поиск заметок").assertIsDisplayed()
        compose.runOnIdle { SettingsRepository.get(compose.activity).setUiLanguage(UiLanguage.ENGLISH) }
        compose.onNodeWithContentDescription("Search notes").assertIsDisplayed()
        compose.runOnIdle { SettingsRepository.get(compose.activity).setUiLanguage(UiLanguage.RUSSIAN) }
        compose.onNodeWithContentDescription("Поиск заметок").assertIsDisplayed()
    }
}
