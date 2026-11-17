package com.notessync.app.ui.editor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * Этап 80: у каждой функции одна точка входа.
 * Цвет — только сверху, в меню ⋮; «Сделать заметкой» — только компактной кнопкой в строке задачи;
 * формат текста — только в одной панели оформления.
 */
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

    @Test fun `task row has one conversion button and no duplicate colour entry`() {
        editor(task = true)
        compose.onNodeWithTag("editor-to-note").assertExists()
        assertEquals(1, count("Сделать заметкой"))
        // Цвет задачи в строке больше не дублируется — он только в меню ⋮.
        assertEquals(0, count("Цвет задачи"))
        // Формат текста остался ровно в одной панели: верхней кнопки «Aa» больше нет.
        assertEquals(0, count("Полное оформление"))
        assertEquals(1, count("Оформление текста"))

        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Цвет задачи").assertExists()
        // «Сделать заметкой» доступно только компактной кнопкой, в меню пункта нет.
        compose.onAllNodesWithText("Сделать заметкой").assertCountEquals(0)
        compose.onAllNodesWithText("Сделать задачей").assertCountEquals(0)
    }

    @Test fun `compact checkbox actually converts the task back into a note`() {
        val vm = editor(task = true)
        compose.onNodeWithTag("editor-to-note").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !vm.isTask }
        assertFalse(vm.isTask)
        compose.onNodeWithText("Заметка").assertExists()
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Сделать задачей").assertExists()
        compose.onNodeWithText("Цвет заметки").assertExists()
        compose.onAllNodesWithText("Сделать заметкой").assertCountEquals(0)
    }

    @Test fun `note has exactly one conversion and one colour entry in the menu`() {
        val vm = editor(task = false)
        assertFalse(vm.isTask)
        // У заметки компактной кнопки превращения нет — только пункт меню.
        compose.onAllNodesWithTag("editor-to-note").assertCountEquals(0)
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Сделать задачей").assertExists()
        compose.onNodeWithText("Цвет заметки").assertExists()
        compose.onAllNodesWithText("Цвет задачи").assertCountEquals(0)
        assertTrue(count("Оформление текста") == 1)
    }
}
