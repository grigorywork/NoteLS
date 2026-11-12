package com.notessync.app.ui.notes

import android.app.Application
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import com.notessync.app.ui.screens.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeActionsTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setNotesLayout(NotesLayout.LIST)
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun teardown() { store.clear(); AppDatabase.closeInstance() }
    private fun start() {
        compose.setContent {
            val vm = remember { NotesViewModel(app).also { store.put("home", it) } }
            MaterialTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) {
                NotesListScreen({}, {}, {}, {}, {}, { _, _ -> }, viewModel = vm)
            } }
        }
    }
    private fun awaitCard(id: Long) = compose.waitUntil(10_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(350))
        compose.waitForIdle(); compose.onAllNodesWithTag("note-card-$id").fetchSemanticsNodes().isNotEmpty()
    }
    private fun trash() {
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Корзина").performClick()
    }
    @Test fun `home long press selects multiple notes search focuses and trash restores all`() {
        val db = AppDatabase.get(app)
        val first = runBlocking { db.noteDao().upsert(NoteEntity(title = "First", content = "Body")) }
        val second = runBlocking { db.noteDao().upsert(NoteEntity(title = "Second")) }
        start(); awaitCard(first)
        compose.onNodeWithContentDescription("Поиск заметок").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithTag("note-card-$first").performTouchInput { longClick() }
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Выбрать все найденные").performClick()
        compose.onNodeWithText("Выбрано: 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("В корзину").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithTag("note-card-$first").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertTrue(db.noteDao().getById(first)!!.isDeleted); assertTrue(db.noteDao().getById(second)!!.isDeleted) }
        trash(); awaitCard(first)
        compose.onNodeWithText("Восстановить все").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithTag("note-card-$first").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertFalse(db.noteDao().getById(first)!!.isDeleted); assertFalse(db.noteDao().getById(second)!!.isDeleted) }
    }
    @Test fun `trash selected permanent deletion requires confirmation and grid keeps pin actions`() {
        val db = AppDatabase.get(app)
        val first = runBlocking { db.noteDao().upsert(NoteEntity(title = "Pinned")) }
        val second = runBlocking { db.noteDao().upsert(NoteEntity(title = "Discard", isDeleted = true)) }
        SettingsRepository.get(app).setNotesLayout(NotesLayout.GRID)
        start(); awaitCard(first)
        compose.onNode(hasContentDescription("Действия") and hasAnyAncestor(hasTestTag("note-card-$first"))).performClick()
        compose.onNodeWithText("Закрепить").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("Закреплено", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertTrue(db.noteDao().getById(first)!!.isPinned) }
        trash(); awaitCard(second)
        compose.onNodeWithTag("note-card-$second").performClick()
        compose.onNodeWithContentDescription("Удалить выбранные навсегда").performClick()
        compose.onNodeWithText("Удалить навсегда?").assertIsDisplayed()
        runBlocking { assertFalse(db.noteDao().getById(second)!!.isPurged) }
        compose.onNodeWithText("Отмена").performClick()
        runBlocking { assertFalse(db.noteDao().getById(second)!!.isPurged) }
        compose.onNodeWithContentDescription("Удалить выбранные навсегда").performClick()
        compose.onNodeWithText("Удалить навсегда").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithTag("note-card-$second").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertTrue(db.noteDao().getById(second)!!.isPurged) }
        runBlocking { assertFalse(db.noteDao().getById(first)!!.isDeleted) }
    }
}
