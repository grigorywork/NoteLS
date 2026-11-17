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
import com.notessync.app.settings.FormattingBarPosition
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.screens.NoteEditorScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Этап 79: панель оформления одна — она не дублируется и переезжает сверху вниз. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormattingPanelPositionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()

    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply {
            setUiLanguage(UiLanguage.RUSSIAN)
            setBackupFrequency(BackupFrequency.MANUAL)
            setAdvancedFormatting(true)
            setFormattingBarPosition(FormattingBarPosition.BOTTOM)
        }
    }

    @After fun after() { store.clear(); AppDatabase.closeInstance() }

    private fun editor(): NoteEditorViewModel {
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "Запись", content = "Первая строка")) }
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

    private fun formattingTools() = compose.onAllNodesWithContentDescription("Оформление текста").fetchSemanticsNodes().size

    @Test fun `default keeps exactly one panel at the bottom`() {
        editor()
        compose.onNodeWithTag("formatting-panel-bottom").assertExists()
        compose.onNodeWithTag("formatting-panel-top").assertDoesNotExist()
        assertEquals(1, formattingTools())
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("formatting-panel-bottom").fetchSemanticsNode().boundsInRoot
        assertTrue("панель снизу", panel.center.y > viewport.center.y)
    }

    @Test fun `stored top position draws the only panel above the text`() {
        SettingsRepository.get(app).setFormattingBarPosition(FormattingBarPosition.TOP)
        editor()
        compose.onNodeWithTag("formatting-panel-top").assertExists()
        compose.onNodeWithTag("formatting-panel-bottom").assertDoesNotExist()
        assertEquals(1, formattingTools())
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("formatting-panel-top").fetchSemanticsNode().boundsInRoot
        assertTrue("панель сверху", panel.center.y < viewport.center.y)
    }

    @Test fun `more menu moves the panel to the top and back without duplicates`() {
        editor()
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Панель сверху").performClick()
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("formatting-panel-top").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("formatting-panel-bottom").assertDoesNotExist()
        assertEquals(1, formattingTools())
        assertEquals(FormattingBarPosition.TOP, SettingsRepository.get(app).state.value.formattingBarPosition)

        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Панель снизу").performClick()
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            compose.onAllNodesWithTag("formatting-panel-bottom").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("formatting-panel-top").assertDoesNotExist()
        assertEquals(1, formattingTools())
        assertEquals(FormattingBarPosition.BOTTOM, SettingsRepository.get(app).state.value.formattingBarPosition)
    }
}
