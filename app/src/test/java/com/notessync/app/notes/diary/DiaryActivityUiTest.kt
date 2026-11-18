package com.notessync.app.notes.diary

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Actual app navigation/lifecycle under Native Robolectric; not a physical phone test. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DiaryActivityUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private lateinit var diary: NotebookEntity
    private lateinit var privateNote: NoteEntity
    private lateinit var legacy: NoteEntity
    private lateinit var dated: NoteEntity
    private lateinit var task: NoteEntity
    private val fixture = object : ExternalResource() {
        override fun before() {
            AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); DiarySession.lock(); ProtectionSession.lockAll()
            SettingsRepository.get(app).apply {
                setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL)
                setThemeMode(ThemeMode.DARK); setNotesLayout(NotesLayout.LIST); setFontScale(SettingsRepository.FONT_NORMAL)
                setArchiveCompletedTasks(false)
            }
            runBlocking {
                diary = PrivateDiary.create(db, PasswordProtector.create("fixture-diary-pass"), 1)
                fun note(id: Long, title: String, content: String = "", notebook: Long? = null, isTask: Boolean = false, day: Long? = null) =
                    NoteEntity(id = id, title = title, content = content, notebookId = notebook, isTask = isTask, dayKey = day)
                privateNote = note(1, "PRIVATE TITLE 56", "PRIVATE BODY 56", diary.id)
                legacy = note(2, "Legacy to move", "Keep legacy body", day = 20_000)
                dated = note(3, "Dated note to keep", day = 20_000)
                task = note(4, "Task to choose", isTask = true, day = 20_000)
                for (entry in listOf(privateNote, legacy, dated, task)) db.noteDao().upsert(entry)
                val tag = db.tagDao().insert(TagEntity(name = DiaryEntries.TAG_NAME))
                db.tagDao().attachToNote(NoteTagCrossRef(legacy.id, tag))
            }
        }
        override fun after() { DiarySession.lock(); ProtectionSession.lockAll(); AppDatabase.closeInstance() }
    }
    val compose = AndroidComposeTestRule(ActivityScenarioRule(MainActivity::class.java)) { rule ->
        lateinit var activity: MainActivity
        rule.scenario.onActivity { activity = it }
        activity
    }
    @get:Rule val chain: RuleChain = RuleChain.outerRule(fixture).around(compose)
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(100)); compose.waitForIdle(); condition()
    }
    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun open() {
        await { exists(hasContentDescription("Меню")) }
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNode(hasText("Дневник") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        await { exists(hasTestTag("diary-password")) }
    }
    private fun enter(password: String) {
        compose.onNodeWithTag("diary-password").performTextReplacement(password)
        compose.onNodeWithTag("diary-enter").performClick()
    }
    private fun unlock() { open(); enter("fixture-diary-pass"); await { exists(hasTestTag("diary-row-${privateNote.id}")) } }

    @Test fun `private content requires password survives a quick background switch and explicit diary exit relocks`() {
        await { exists(hasText(legacy.title)) }
        compose.onNodeWithText(privateNote.title).assertDoesNotExist()
        open(); enter("wrong-password")
        await { exists(hasText("Неверный пароль или запись изменилась")) }
        assertFalse(ProtectionSession.isUnlocked(diary)); compose.onNodeWithText(privateNote.title).assertDoesNotExist()
        enter("fixture-diary-pass"); await { exists(hasTestTag("diary-row-${privateNote.id}")) }
        compose.onNodeWithTag("diary-row-${privateNote.id}").performClick()
        await { exists(hasTestTag("editor-body")) }
        compose.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(privateNote.content.length))
        compose.onNodeWithTag("editor-body").performTextInput(" live")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { exists(hasText(privateNote.content + " live") and hasSetTextAction()) }
        compose.onNodeWithContentDescription("Назад").performClick()
        await { exists(hasTestTag("diary-row-${privateNote.id}")) }
        runBlocking {
            val after = db.noteDao().getById(privateNote.id)!!
            assertEquals(privateNote.content + " live", after.content); assertEquals(diary.id, after.notebookId)
        }
        compose.onNodeWithContentDescription("Назад").performClick()
        await { exists(hasContentDescription("Меню")) }
        compose.onNodeWithText(privateNote.title).assertDoesNotExist()
        assertFalse(ProtectionSession.isUnlocked(diary))
        open(); compose.onNodeWithTag("diary-row-${privateNote.id}").assertDoesNotExist()
    }

    @Test fun `migration preselects legacy journal and moves a dated task only after explicit opt in and selection`() {
        unlock()
        compose.onNodeWithContentDescription("Действия дневника").performClick()
        compose.onNodeWithText("Перенести прежние записи").performClick()
        await { exists(hasText(legacy.title)) }
        compose.onNodeWithText(task.title).assertDoesNotExist()
        await { exists(hasText("Перенести 1")) }
        compose.onNodeWithText("Показать задачи с датой").performClick()
        await { exists(hasText(task.title)) && exists(hasText("Перенести 1")) }
        compose.onNodeWithText(task.title).performClick()
        await { exists(hasText("Перенести 2")) }
        compose.onNodeWithText("Перенести 2").performClick()
        await { exists(hasTestTag("diary-row-${legacy.id}")) && exists(hasTestTag("diary-row-${task.id}")) }
        runBlocking {
            assertEquals(legacy.content, db.noteDao().getById(legacy.id)!!.content)
            assertEquals(diary.id, db.noteDao().getById(legacy.id)!!.notebookId)
            assertEquals(diary.id, db.noteDao().getById(task.id)!!.notebookId)
            assertEquals(dated, db.noteDao().getById(dated.id))
        }
        compose.onNodeWithContentDescription("Назад").performClick()
        await { exists(hasContentDescription("Меню")) }
        compose.onNodeWithText(legacy.title).assertDoesNotExist(); compose.onNodeWithText(task.title).assertDoesNotExist()
        compose.onNodeWithText(dated.title).assertIsDisplayed()
    }
}
