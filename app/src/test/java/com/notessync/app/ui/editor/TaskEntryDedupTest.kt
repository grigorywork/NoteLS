package com.notessync.app.ui.editor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.BackupFrequency
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.screens.NoteEditorScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Updated user choice: task colour and a persistent task-mode checkbox live in the row.
 * The checkbox stays visible in note mode, and neither action is duplicated in More.
 * Text formatting still has one panel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskEntryDedupTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()

    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply {
            setUiLanguage(UiLanguage.RUSSIAN)
            setBackupFrequency(BackupFrequency.MANUAL)
            setAdvancedFormatting(true)
        }
    }

    @After fun after() { store.clear(); AppDatabase.closeInstance() }

    private fun editor(task: Boolean): NoteEditorViewModel {
        val id = runBlocking {
            AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "План", content = "Текст", isTask = task))
        }
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { store.put("editor", it) }
        compose.setContent {
            NoteSyncTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.requiredSize(360.dp, 720.dp).testTag("viewport")) {
                    NoteEditorScreen({}, {}, vm)
                }
            }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); vm.isReady }
        return vm
    }

    private fun count(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    @Test fun `task row has one persistent checked toggle and colour with no menu duplicates`() {
        editor(task = true)
        compose.onNodeWithTag("editor-task-toggle").assertIsOn()
        compose.onNodeWithTag("editor-task-color").assertExists()
        assertEquals(1, count("Сделать заметкой")); assertEquals(1, count("Цвет задачи"))
        assertEquals(0, count("Полное оформление")); assertEquals(1, count("Оформление текста"))
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onAllNodesWithText("Цвет задачи").assertCountEquals(0)
        compose.onAllNodesWithText("Сделать заметкой").assertCountEquals(0)
        compose.onAllNodesWithText("Сделать задачей").assertCountEquals(0)
    }
    @Test fun `converting to note leaves the same checkbox visible and unchecked`() {
        val vm = editor(task = true)
        compose.onNodeWithTag("editor-task-toggle").assertIsOn().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !vm.isTask }
        assertFalse(vm.isTask)
        compose.onNodeWithTag("editor-task-toggle").assertIsOff().assertExists()
        compose.onNodeWithTag("editor-task-color").assertDoesNotExist()
        assertEquals(1, count("Сделать задачей"))
        compose.onNodeWithText("Заметка").assertExists()
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Цвет заметки").assertExists()
        compose.onAllNodesWithText("Сделать задачей").assertCountEquals(0)
        compose.onAllNodesWithText("Сделать заметкой").assertCountEquals(0)
    }
    @Test fun `ordinary note checkbox switches into a task without disappearing or adding menu conversion`() {
        val vm = editor(task = false)
        compose.onNodeWithTag("editor-task-toggle").assertIsOff()
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Цвет заметки").assertExists()
        compose.onAllNodesWithText("Сделать задачей").assertCountEquals(0)
        compose.onNode(isPopup()).performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("editor-task-toggle").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); vm.isTask }
        assertTrue(vm.isTask)
        compose.onNodeWithTag("editor-task-toggle").assertIsOn().assertExists()
        compose.onNodeWithTag("editor-task-color").assertExists()
        assertEquals(1, count("Цвет задачи")); assertEquals(1, count("Оформление текста"))
    }
}
