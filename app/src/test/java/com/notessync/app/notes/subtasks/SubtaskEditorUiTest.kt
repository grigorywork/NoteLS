package com.notessync.app.notes.subtasks

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
@Config(sdk=[28],application=Application::class,qualifiers="ru-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubtaskEditorUiTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=RuntimeEnvironment.getApplication()
    private val models=ViewModelStore()
    private val keyboard=mutableIntStateOf(0)
    private var density=1f
    private lateinit var view:View
    @Before fun before() {
        AppDatabase.closeInstance();app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL);setUiLanguage(UiLanguage.RUSSIAN);setEditorHistory(true) }
    }
    @After fun after() { models.clear();AppDatabase.closeInstance();SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN) }
    private fun editor(english:Boolean=false):NoteEditorViewModel {
        if(english) SettingsRepository.get(app).setUiLanguage(UiLanguage.ENGLISH)
        val id=runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title="Plan",content="Main note text stays visible")) }
        val vm=NoteEditorViewModel(app,SavedStateHandle(mapOf("noteId" to id))).also { models.put("editor",it) }
        compose.setContent {
            view=LocalView.current;density=LocalDensity.current.density
            NoteSyncTheme(darkTheme=true,dynamicColor=false,fontScale=if(english) 1.15f else 1f) {
                Box(Modifier.requiredSize(320.dp,640.dp).testTag("subtask-viewport")) {
                    NoteEditorScreen({}, {}, vm, imeInsets=WindowInsets(bottom=keyboard.intValue))
                }
            }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); vm.isReady };return vm
    }
    private fun add(vm:NoteEditorViewModel):String {
        compose.onNodeWithTag("add-subtask").performScrollTo().performClick()
        compose.waitUntil(15_000) { vm.subtasks.isNotEmpty() };return vm.subtasks.last().id
    }
    private fun screenshot(name:String) {
        val box=compose.onNodeWithTag("subtask-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap=Bitmap.createBitmap(box.width.toInt(),box.height.toInt(),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.translate(-box.left,-box.top);view.draw(canvas)
            File("build/reports/subtask-preview/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun `small inline plus creates an editable child and its two independent controls are to the right`() {
        val vm=editor();val id=add(vm)
        compose.onNodeWithTag("subtask-text-$id").performScrollTo().performTextInput("First item")
        compose.onNodeWithTag("subtask-watch-$id").performScrollTo().performClick()
        compose.waitUntil(15_000) { vm.subtasks.single().startedAt!=null }
        compose.onNodeWithTag("subtask-timer-$id").performClick();compose.onNodeWithText("1 минута").performClick()
        compose.waitUntil(15_000) { vm.subtasks.single().countdownEndAt!=null }
        val text=compose.onNodeWithTag("subtask-text-$id").fetchSemanticsNode().boundsInRoot
        val watch=compose.onNodeWithTag("subtask-watch-$id").fetchSemanticsNode().boundsInRoot
        val timer=compose.onNodeWithTag("subtask-timer-$id").fetchSemanticsNode().boundsInRoot
        assertTrue(watch.left>=text.right-1);assertTrue(timer.left>=watch.right-1)
        assertEquals("First item",vm.subtasks.single().text);assertEquals("Main note text stays visible",vm.contentField.text)
        screenshot("inline-ru-320")
        compose.onNodeWithTag("subtask-check-$id").performClick()
        compose.waitUntil(15_000) { vm.subtasks.single().completed };assertNull(vm.subtasks.single().startedAt);assertNull(vm.subtasks.single().countdownEndAt)
    }
    @Test fun `large English font and simulated IME keep the focused child and timing controls in the viewport`() {
        val vm=editor(true);val id=add(vm)
        compose.runOnIdle { keyboard.intValue=(240*density).toInt() }
        compose.onNodeWithTag("subtask-text-$id").performScrollTo().performTextInput("Focused child")
        compose.onNodeWithTag("subtask-text-$id").assertIsDisplayed();compose.onNodeWithTag("subtask-timer-$id").assertIsDisplayed()
        val box=compose.onNodeWithTag("subtask-text-$id").fetchSemanticsNode().boundsInRoot
        assertTrue(box.bottom<=400*density+2);assertTrue(box.height>0)
        screenshot("inline-en-large-ime-320")
    }
    @Test fun `deleting and undoing a child preserves main body and preview disables the inline add action`() {
        val vm=editor();val id=add(vm)
        compose.onNodeWithTag("subtask-text-$id").performScrollTo().performTextInput("Keep me")
        compose.onNodeWithContentDescription("Параметры подзадачи").performClick()
        compose.onNodeWithText("Удалить подзадачу").performClick();compose.waitUntil(15_000) { vm.subtasks.isEmpty() }
        compose.runOnIdle { vm.undo() };compose.waitUntil(15_000) { vm.subtasks.size==1 }
        assertEquals("Keep me",vm.subtasks.single().text);assertEquals("Main note text stays visible",vm.contentField.text)
        compose.runOnIdle { vm.previewMode=true }
        compose.onNodeWithTag("add-subtask").performScrollTo().assertIsNotEnabled()
    }
}
