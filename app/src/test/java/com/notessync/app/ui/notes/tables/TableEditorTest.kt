package com.notessync.app.ui.notes.tables

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import com.notessync.app.notes.history.EditorSelection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.notes.tables.*
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.screens.NoteEditorScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose/native layout simulation only, not a device performance measurement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `narrow grid composes visible rows and keeps horizontal positions aligned`() {
        val model = mutableStateOf(NoteTables().add("t", 20, 10).editCell(TableCellRef("t", 0, 0), "Текст"))
        val restore = mutableLongStateOf(0)
        val restoredCell = mutableStateOf<TableCellRef?>(null)
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(320.dp, 400.dp)) {
                TableGrid(model.value.blocks.single(), { ref, _, value -> model.value = model.value.editCell(ref, value.text) },
                    { _, _, _ -> }, { _, _, _ -> }, restoreVersion = restore.longValue,
                    restoreCell = restoredCell.value, restoreSelection = EditorSelection(0))
            }
        } }
        compose.onNodeWithTag("cell-0-0").assertIsDisplayed()
        compose.onNodeWithTag("cell-19-0").assertDoesNotExist()
        val cells = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        assertTrue(cells in 1 until 200)
        val beforeHeader = compose.onNodeWithTag("column-handle-1").fetchSemanticsNode().positionInRoot.x
        val beforeCell = compose.onNodeWithTag("cell-0-1").fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithTag("table-columns").performTouchInput { swipeLeft() }
        val afterHeader = compose.onNodeWithTag("column-handle-1").fetchSemanticsNode().positionInRoot.x
        val afterCell = compose.onNodeWithTag("cell-0-1").fetchSemanticsNode().positionInRoot.x
        assertTrue(afterHeader < beforeHeader)
        assertEquals(beforeHeader - afterHeader, beforeCell - afterCell, 1.5f)
        compose.onNodeWithTag("table-rows").performScrollToIndex(19)
        compose.onNodeWithTag("cell-19-0").assertExists()
        compose.runOnIdle { restoredCell.value = TableCellRef("t", 0, 0); restore.longValue++ }
        compose.onNodeWithTag("cell-0-0").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0)))
    }

    @Test fun `cells arrows and drag handles update dimensions once per completed gesture`() {
        val model = mutableStateOf(NoteTables().add("t", 2, 2))
        var columnCommits = 0; var rowCommits = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(320.dp, 500.dp)) {
                TableGrid(model.value.blocks.single(), { ref, _, value -> model.value = model.value.editCell(ref, value.text) },
                    { id, column, width -> columnCommits++; model.value = model.value.resizeColumn(id, column, width) },
                    { id, row, height -> rowCommits++; model.value = model.value.resizeRow(id, row, height) })
            }
        } }
        compose.onNodeWithTag("cell-0-0").performTextInput("Ячейка 😀")
        assertEquals("Ячейка 😀", model.value.blocks[0].cells[0][0])
        compose.onNodeWithContentDescription("Шире").performClick()
        compose.onNodeWithContentDescription("Выше").performClick()
        assertEquals(176, model.value.blocks[0].columnWidths[0]); assertEquals(96, model.value.blocks[0].rowHeights[0])
        compose.onNodeWithTag("column-handle-0").performTouchInput { swipeRight() }
        assertEquals(2, columnCommits); assertTrue(model.value.blocks[0].columnWidths[0] > 176)
        compose.onNodeWithTag("row-handle-0").performTouchInput { swipeDown() }
        assertEquals(2, rowCommits); assertTrue(model.value.blocks[0].rowHeights[0] > 96)
    }

    @Test fun `full editor creates and reopens a table while hiding feature preserves data`() {
        val store = ViewModelStore(); val app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setShowTables(true)
        lateinit var vm: NoteEditorViewModel
        try {
            compose.setContent {
                vm = remember { NoteEditorViewModel(app, SavedStateHandle()).also { store.put("e", it) } }
                MaterialTheme { NoteEditorScreen(onBack = {}, onDeleted = {}, viewModel = vm) }
            }
            compose.waitUntil(10_000) { compose.waitForIdle(); vm.isReady }
            compose.onNodeWithContentDescription("Таблица").performClick()
            compose.onNodeWithText("Строки: 1–20").performTextReplacement("2")
            compose.onNodeWithText("Столбцы: 1–10").performTextReplacement("2")
            compose.onNodeWithText("Добавить").performClick()
            compose.onNodeWithTag("cell-0-0").performTextInput("Сохранить")
            compose.onNodeWithContentDescription("Закрыть таблицу").performClick()
            compose.onNodeWithContentDescription("Таблица").performClick()
            compose.onNodeWithText("Таблица 2 × 2 · 1").performClick()
            compose.onNodeWithTag("cell-0-0").assertTextEquals("Сохранить")
            compose.onNodeWithContentDescription("Закрыть таблицу").performClick()
            compose.runOnIdle { SettingsRepository.get(app).setShowTables(false) }
            compose.onNodeWithContentDescription("Таблица").assertDoesNotExist()
            assertEquals("Сохранить", vm.tables!!.blocks[0].cells[0][0])
        } finally { store.clear(); AppDatabase.closeInstance() }
    }
}
