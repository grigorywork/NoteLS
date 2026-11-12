package com.notessync.app.ui.analytics

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.model.AnalyticsNotebook
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.screens.AnalyticsScreen
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN)
    }
    @After fun after() { store.clear(); compose.waitForIdle(); AppDatabase.closeInstance() }

    private fun awaitEntries(value: Int) {
        compose.waitUntil(10_000) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100)); compose.waitForIdle()
            compose.onAllNodes(hasText(value.toString()) and hasAnyAncestor(hasTestTag("stats-entries"))).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun count(tag: String, value: Int) {
        compose.onNode(hasText(value.toString()) and hasAnyAncestor(hasTestTag(tag))).assertExists()
    }
    private fun select(tag: String) {
        compose.onNodeWithTag("analytics-filter").performScrollTo().performClick()
        compose.onNodeWithTag("analytics-scope-$tag").performScrollTo().performClick()
    }
    @Test fun `general counters remain available without completed tasks and filter by notebook or unfiled`() {
        val db = AppDatabase.get(app)
        val ids = runBlocking {
            val work = db.notebookDao().upsert(NotebookEntity(name = "Work"))
            val empty = db.notebookDao().upsert(NotebookEntity(name = "Empty"))
            val private = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true))
            db.noteDao().upsert(NoteEntity(title = "Work note", notebookId = work))
            db.noteDao().upsert(NoteEntity(title = "Unfiled note"))
            db.noteDao().upsert(NoteEntity(title = "Active", notebookId = work, isTask = true))
            db.noteDao().upsert(NoteEntity(title = "Hidden", notebookId = private, isTask = true))
            work to empty
        }
        val vm = AnalyticsViewModel(app).also { store.put("analytics", it) }
        compose.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { AnalyticsScreen({}, {}, vm) } } }
        awaitEntries(3); count("stats-notes", 2); count("stats-tasks", 1); count("stats-completed", 0); count("stats-active", 1)
        select("book-${ids.first}")
        awaitEntries(2); count("stats-notes", 1); count("stats-tasks", 1)
        select("unfiled")
        awaitEntries(1); count("stats-notes", 1); count("stats-tasks", 0)
        select("book-${ids.second}")
        awaitEntries(0); count("stats-notes", 0); count("stats-tasks", 0)
        compose.onNodeWithText("В этом разделе пока нет записей.").assertExists()
        select("all"); awaitEntries(3)
    }

    @Test fun `notebook picker lazily opens at selected row among two thousand notebooks`() {
        val books = (1L..2_000L).map { AnalyticsNotebook(it, "Notebook $it") }
        var selected: AnalyticsScope? = null
        compose.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) {
            AnalyticsScopePicker(AnalyticsScope.Notebook(1_999), books, true) { selected = it }
        } } }
        compose.onNodeWithTag("analytics-filter").performClick()
        compose.onNodeWithTag("analytics-scope-book-1999").assertIsDisplayed()
        compose.onNodeWithTag("analytics-scope-book-1").assertDoesNotExist()
        compose.onNodeWithTag("analytics-scope-book-2000").performClick()
        compose.runOnIdle { assertEquals(AnalyticsScope.Notebook(2_000), selected) }
        compose.onNodeWithTag("analytics-notebooks").assertDoesNotExist()
    }
}
