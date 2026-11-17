package com.notessync.app.tasks

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.*
import com.notessync.app.ui.screens.NotesListScreen
import com.notessync.app.ui.tasks.*
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
class TaskDeadlineListUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setNotesLayout(NotesLayout.LIST); setBackupFrequency(BackupFrequency.MANUAL) }
    }
    @After fun after() { models.clear(); AppDatabase.closeInstance() }
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(350)); compose.waitForIdle(); condition()
    }
    @Test fun `real drawer overdue view combines a priority filter and returns to all unfinished without stale cards`() {
        val db = AppDatabase.get(app); val now = System.currentTimeMillis()
        val high = runBlocking { db.noteDao().upsert(NoteEntity(title = "High overdue", isTask = true, taskPriority = 3, taskDueAt = now - 1_000)) }
        val low = runBlocking { db.noteDao().upsert(NoteEntity(title = "Low overdue", isTask = true, taskPriority = 1, taskDueAt = now - 1_000)) }
        val future = runBlocking { db.noteDao().upsert(NoteEntity(title = "Future", isTask = true, taskPriority = 3, taskDueAt = now + 3_600_000)) }
        lateinit var vm: NotesViewModel
        compose.setContent {
            vm = remember { NotesViewModel(app).also { models.put("home", it) } }
            MaterialTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { NotesListScreen({}, {}, {}, {}, {}, { _, _ -> }, viewModel = vm) } }
        }
        await { compose.onAllNodesWithTag("note-card-$high").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithContentDescription("Разделы заметок").performClick()
        compose.onNodeWithText("Просроченные").performClick()
        await { !vm.results.value?.key?.filter.let { it != NoteFilter.Overdue } }
        compose.onNodeWithTag("note-card-$future").assertDoesNotExist()
        compose.onNodeWithText("Все приоритеты").performScrollTo().performClick(); compose.onNodeWithText("Высокий").performClick()
        await { vm.results.value?.key?.tasks?.priority == 3 }
        compose.runOnIdle { assertEquals(NoteFilter.Overdue, vm.filter.value) }
        compose.onNodeWithTag("note-card-$high").assertExists(); compose.onNodeWithTag("note-card-$low").assertDoesNotExist()
        compose.onNode(hasText("Просроченные") and hasAnyAncestor(hasTestTag("task-filters"))).performClick()
        compose.onNodeWithText("Любой срок").performClick()
        await { vm.results.value?.key?.filter == NoteFilter.Unfinished && vm.results.value?.key?.tasks?.due == TaskDueFilter.ALL }
        compose.onNodeWithTag("note-card-$future").assertExists()
    }
}
