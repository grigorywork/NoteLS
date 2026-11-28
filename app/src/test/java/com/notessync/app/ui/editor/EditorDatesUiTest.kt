package com.notessync.app.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="ru-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorDatesUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val models = ViewModelStore()
    private val keyboard = mutableIntStateOf(0)
    private var density = 1f
    private lateinit var view: View
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply {
            setBackupFrequency(BackupFrequency.MANUAL); setUiLanguage(UiLanguage.RUSSIAN)
            setFormattingBarPosition(FormattingBarPosition.BOTTOM)
        }
    }
    @After fun after() {
        models.clear(); AppDatabase.closeInstance()
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setFormattingBarPosition(FormattingBarPosition.BOTTOM) }
    }
    private fun editor(english: Boolean = false, top: Boolean = false): NoteEditorViewModel {
        SettingsRepository.get(app).apply {
            setUiLanguage(if (english) UiLanguage.ENGLISH else UiLanguage.RUSSIAN)
            setFormattingBarPosition(if (top) FormattingBarPosition.TOP else FormattingBarPosition.BOTTOM)
        }
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(content="A short note", createdAt=1_700_000_000_000, updatedAt=1_700_100_000_000)) }
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { models.put("editor", it) }
        compose.setContent {
            view=LocalView.current; density=LocalDensity.current.density
            NoteSyncTheme(darkTheme=true, dynamicColor=false, fontScale=if (english) 1.6f else 1f) {
                Box(Modifier.requiredSize(320.dp, 640.dp).testTag("dates-viewport")) {
                    NoteEditorScreen({}, {}, vm, imeInsets=WindowInsets(bottom=keyboard.intValue))
                }
            }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); vm.isReady && vm.editorDates != null }
        return vm
    }
    private fun geometry() {
        val created=compose.onNodeWithTag("editor-created").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val updated=compose.onNodeWithTag("editor-modified").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNodeWithTag("dates-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(created.right <= updated.left); assertEquals(created.top, updated.top, 1f)
        assertTrue(created.left >= viewport.left); assertTrue(updated.right <= viewport.right)
        assertTrue(updated.bottom <= viewport.bottom-keyboard.intValue+1f)
        compose.onNodeWithTag("editor-body").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        val bounds=compose.onNodeWithTag("dates-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val image=Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image); canvas.translate(-bounds.left, -bounds.top); view.draw(canvas)
            val output=File("build/reports/editor-dates-preview/$name.png").apply { parentFile.mkdirs() }
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
    }
    @Test fun `creation is left and modification is right beneath bottom formatting in RU320`() {
        editor(); geometry(); compose.onNodeWithText("Создано").assertIsDisplayed(); compose.onNodeWithText("Изменено").assertIsDisplayed()
        val toolbar=compose.onNodeWithTag("formatting-panel-bottom").fetchSemanticsNode().boundsInRoot
        val footer=compose.onNodeWithTag("editor-dates").fetchSemanticsNode().boundsInRoot
        assertTrue(toolbar.bottom <= footer.top+1f); screenshot("dates-ru-320")
    }
    @Test fun `dates stay at bottom with top formatting large English font and fake keyboard`() {
        editor(english=true, top=true)
        compose.runOnIdle { keyboard.intValue=(240*density).toInt() }; compose.waitForIdle()
        geometry(); compose.onNodeWithText("Created").assertIsDisplayed(); compose.onNodeWithText("Modified").assertIsDisplayed()
        compose.onNodeWithTag("formatting-panel-top").assertIsDisplayed(); screenshot("dates-en-large-ime-320")
    }
}
