package com.notessync.app.ui.navigation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class NotebookActivityNavigationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val work = NotebookEntity(id = 1, name = "Работа")
    private val personal = NotebookEntity(id = 2, name = "Личное")
    private val fixture = object : ExternalResource() {
        override fun before() {
            AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
            SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL); setArchiveCompletedTasks(false) }
            runBlocking { AppDatabase.get(app).apply { notebookDao().upsert(work); notebookDao().upsert(personal); noteDao().upsert(NoteEntity(title = "Общая запись")) } }
        }
        override fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    }
    val compose = AndroidComposeTestRule(ActivityScenarioRule(MainActivity::class.java)) { rule ->
        lateinit var activity: MainActivity; rule.scenario.onActivity { activity = it }; activity
    }
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun await(check: () -> Boolean) { compose.waitUntil(15_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100)); compose.waitForIdle(); check()
    } }
    private fun awaitNode(matcher: SemanticsMatcher) = await { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    private fun selectBook(name: String) {
        awaitNode(hasContentDescription("Меню")); compose.onNodeWithContentDescription("Меню").performClick()
        awaitNode(hasText(name)); compose.onNodeWithText(name).performScrollTo().performClick()
        awaitNode(hasContentDescription("Новая задача")); compose.waitForIdle()
    }
    private fun create(task: Boolean, title: String, body: String) {
        selectBook(work.name)
        compose.onNodeWithContentDescription(if (task) "Новая задача" else "Новая заметка").performClick()
        await { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2 }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(title)
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(body)
        compose.onNodeWithContentDescription("Назад").performClick(); awaitNode(hasContentDescription("Меню"))
        awaitNode(hasText(title))
    }
    @Test fun `creation from a notebook survives Activity recreation and remains absent from other notebooks`() {
        create(false, "Внутри Работы", "Текст из блокнота")
        val saved = runBlocking { AppDatabase.get(app).noteDao().observeAll().first().single { it.title == "Внутри Работы" } }
        assertEquals(work.id, saved.notebookId)
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle(); compose.activityRule.scenario.recreate()
        selectBook(personal.name); compose.onNodeWithText(saved.title).assertDoesNotExist()
        selectBook(work.name); awaitNode(hasText(saved.title)); compose.onNodeWithText(saved.title).performClick()
        awaitNode(hasText(saved.content) and hasSetTextAction())
        compose.onNodeWithContentDescription("Назад").performClick(); awaitNode(hasContentDescription("Меню"))
        assertEquals(saved, runBlocking { AppDatabase.get(app).noteDao().getById(saved.id) })
        assertNull(runBlocking { AppDatabase.get(app).noteDao().observeAll().first().single { it.title == "Общая запись" }.notebookId })
    }
    @Test fun `task creation and checking completion keep the original notebook and document`() {
        create(true, "Рабочая задача", "Содержимое задачи")
        val saved = runBlocking { AppDatabase.get(app).noteDao().observeAll().first().single { it.title == "Рабочая задача" } }
        assertEquals(work.id, saved.notebookId); assertTrue(saved.isTask)
        compose.onNodeWithContentDescription("Выполнить задачу: ${saved.title}").performClick()
        await { runBlocking { AppDatabase.get(app).noteDao().getById(saved.id)?.isCompleted == true } }
        val after = runBlocking { AppDatabase.get(app).noteDao().getById(saved.id)!! }
        assertEquals(saved.notebookId, after.notebookId); assertEquals(saved.createdAt, after.createdAt)
        assertEquals(saved.syncId, after.syncId); assertEquals(saved.content, after.content)
        selectBook(personal.name); compose.onNodeWithText(saved.title).assertDoesNotExist()
        selectBook(work.name); awaitNode(hasText(saved.title))
    }
}
