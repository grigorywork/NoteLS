package com.notessync.app.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.screens.NoteEditorScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Compose rendering with simulated insets; no claim about a physical keyboard or phone. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactEditorTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private val inset = mutableIntStateOf(0)
    private var density = 1f
    private lateinit var drawingView: View
    private var fontScale = mutableStateOf(1f)
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL); setEditorHistory(true) }
    }
    @After fun after() { store.clear(); AppDatabase.closeInstance() }
    private fun editor(task: Boolean = true, text: String = "1. Первая\n2. Вторая") : NoteEditorViewModel {
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "План на день", content = text, isTask = task,
            taskCountdownMillis = if (task) 25 * 60_000 else 0)) }
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { store.put("editor", it) }
        compose.setContent {
            density = LocalDensity.current.density
            drawingView = androidx.compose.ui.platform.LocalView.current
            NoteSyncTheme(darkTheme = true, dynamicColor = false, fontScale = fontScale.value) {
                Box(Modifier.requiredSize(320.dp, 640.dp).testTag("viewport")) {
                    NoteEditorScreen({}, {}, vm, imeInsets = WindowInsets(bottom = inset.intValue))
                }
            }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); vm.isReady }
        return vm
    }
    private fun saveScreenshot(name: String) {
        val bounds = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val output = File("build/reports/editor-preview/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            // Native Canvas is synchronous in Robolectric; PixelCopy waits for a device-only redraw signal.
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            drawingView.draw(canvas)
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun `task controls are adjacent and all seven formatting actions fit320dp`() {
        editor()
        val watch = compose.onNodeWithTag("editor-stopwatch").fetchSemanticsNode().boundsInRoot
        val timer = compose.onNodeWithTag("editor-countdown").fetchSemanticsNode().boundsInRoot
        assertTrue(watch.right <= timer.left + 1f); assertEquals(watch.center.y, timer.center.y, 1f)
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("25:00").assertIsDisplayed()
        val labels = listOf("Оформление текста", "Цвет текста", "Подложка", "Выравнивание", "Вид списка", "Таблица", "Вставить")
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val bounds = labels.map { compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        for (i in bounds.indices) {
            assertTrue(labels[i], bounds[i].left >= viewport.left && bounds[i].right <= viewport.right + 1f)
            assertTrue(labels[i], bounds[i].height >= 44 * density - 1)
            if (i > 0) assertTrue(labels[i], bounds[i-1].right <= bounds[i].left + 1f)
        }
        compose.onAllNodes(hasText("Пароль", substring = true)).assertCountEquals(0)
        saveScreenshot("task-320")
        compose.onNodeWithContentDescription("Параметры задачи").performClick()
        compose.onNodeWithText("Срок задачи").assertIsDisplayed()
        compose.onAllNodes(hasText("Пароль", substring = true)).assertCountEquals(0)
    }
    @Test fun `table creation opens from an unfocused note and retains its text`() {
        val vm = editor(task = false, text = "Сохранить этот текст")
        compose.onNodeWithContentDescription("Таблица").performClick()
        compose.onNodeWithText("Новая таблица").assertIsDisplayed()
        compose.onNodeWithText("Добавить").performClick()
        compose.onNodeWithTag("cell-0-0").assertIsDisplayed()
        assertEquals("Сохранить этот текст", vm.contentField.text)
        assertEquals(1, vm.tables!!.blocks.size)
    }
    @Test fun `list menu changes real note input and Enter continues Cyrillic numbering`() {
        val vm = editor(task = false, text = "Первая")
        compose.onNodeWithContentDescription("Вид списка").performClick()
        compose.onNodeWithText("Буквенный").performClick()
        compose.waitForIdle(); assertEquals("а) Первая", vm.contentField.text)
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(vm.contentField.text.length))
        compose.onNodeWithTag("editor-body").performTextInput("\n")
        assertEquals("а) Первая\nб) ", vm.contentField.text)
        compose.onNodeWithTag("editor-body").performTextInput("\n")
        assertEquals("а) Первая\n", vm.contentField.text)
        compose.onNodeWithContentDescription("Таблица").performClick()
        compose.onNodeWithText("Новая таблица").assertIsDisplayed()
    }
    @Test fun `cursor remains above docked toolbar when keyboard opens and font grows`() {
        val vm = editor(text = (1..24).joinToString("\n") { "Строка $it" })
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(vm.contentField.text.length))
        for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { inset.intValue = (260 * density).toInt(); fontScale.value = scale }
            compose.onNodeWithTag("editor-body").performTextInput("!")
            compose.waitForIdle()
            val node = compose.onNodeWithTag("editor-body").fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
            val cursor = layout.single().getCursorRect(vm.contentField.selection.end)
            val toolbar = compose.onNodeWithContentDescription("Таблица").fetchSemanticsNode().boundsInRoot
            val bottom = node.positionInRoot.y + cursor.bottom + 16 * density
            assertTrue("cursor=$bottom toolbar=${toolbar.top}", bottom <= toolbar.top + 2f)
            assertTrue(bottom > 0f)
        }
        saveScreenshot("task-ime-large-font")
    }
    @Test fun `full formatting and alignment preserve selection and undo works from top bar`() {
        val vm = editor(task = false, text = "Текст")
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(0, 5))
        compose.onNodeWithContentDescription("Выравнивание").performClick()
        compose.onNodeWithText("По центру").performClick()
        compose.waitForIdle(); assertEquals("CENTER", vm.activeFormatting[com.notessync.app.notes.text.FormatKind.ALIGN])
        compose.onNodeWithContentDescription("Отменить").performClick()
        compose.waitForIdle(); assertNull(vm.activeFormatting[com.notessync.app.notes.text.FormatKind.ALIGN])
        saveScreenshot("note-320")
    }
    // API28's native Magnifier requires a device SurfaceSession root absent in Robolectric.
    // Exercise the real selection gesture on supported API27, which has no platform magnifier.
    @Config(sdk = [27])
    @Test fun `long press opens contextual formatting without duplicating the dock`() {
        val vm = editor(task = false, text = "Выделяемый текст")
        compose.onNodeWithTag("editor-body").performTouchInput {
            longClick(androidx.compose.ui.geometry.Offset(40 * density, 24 * density))
        }
        compose.onNodeWithContentDescription("Копировать").assertIsDisplayed()
        assertFalse(vm.contentField.selection.collapsed)
        compose.onNodeWithContentDescription("Таблица").assertDoesNotExist()
        compose.onNodeWithContentDescription("Оформление выделения").performClick()
        compose.onNodeWithText("Цвет текста").performClick()
        compose.onNodeWithText("Красный").assertIsDisplayed()
        compose.onNodeWithContentDescription("Таблица").assertDoesNotExist()
        compose.onNodeWithText("Красный").performClick()
        compose.waitForIdle()
        assertEquals("FFE53935", vm.activeFormatting[com.notessync.app.notes.text.FormatKind.COLOR])
    }
}
