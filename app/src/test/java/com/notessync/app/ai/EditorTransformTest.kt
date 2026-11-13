package com.notessync.app.ai

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.notes.text.FormatKind
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorTransformTest {
    private val dispatcher = StandardTestDispatcher(); private val models = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: NoteEditorViewModel
    @Before fun before() {
        Dispatchers.setMain(dispatcher); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL); setEditorHistory(true); setAiEnabled(true) }
        vm = NoteEditorViewModel(app, SavedStateHandle()); models.put("editor", vm); dispatcher.scheduler.runCurrent()
    }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); SettingsRepository.get(app).setAiEnabled(false); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `capture is unchanged until apply and replacing a selection preserves title outside formatting and undo redo`() = runTest(dispatcher) {
        val original = "Before one. two. After"
        vm.onTitleChange(TextFieldValue("Original title")); vm.onContentChange(TextFieldValue(original, TextRange(0, 6)))
        vm.formatSelection(FormatKind.BOLD, "1"); vm.onContentChange(TextFieldValue(original, TextRange(7, 16)))
        val source = vm.captureTransformSource(); assertEquals("one. two.", source.text)
        val db = AppDatabase.get(app); assertEquals(original, db.noteDao().getById(vm.currentNoteId)!!.content)
        assertTrue(vm.applyTransform(source, "- One\n- Two")); assertEquals("Before - One\n- Two After", vm.contentField.text)
        assertEquals("Original title", vm.title); assertEquals("1", vm.formatting.at(1)[FormatKind.BOLD])
        vm.undo(); vm.saveNow(force = true); assertEquals(original, vm.contentField.text)
        vm.redo(); vm.saveNow(force = true); assertEquals("Before - One\n- Two After", db.noteDao().getById(vm.currentNoteId)!!.content)
    }
    @Test fun `later user input revoked access and disabled setting reject a stale transformation`() = runTest(dispatcher) {
        vm.onContentChange(TextFieldValue("Original")); val source = vm.captureTransformSource()
        vm.onContentChange(TextFieldValue("Typed later")); assertFalse(vm.applyTransform(source, "Generated")); assertEquals("Typed later", vm.contentField.text)
        vm.saveNow(force = true); val fresh = vm.captureTransformSource(); val db = AppDatabase.get(app)
        val protected = db.noteDao().getById(vm.currentNoteId)!!.copy(isProtected = true, updatedAt = fresh.access.updatedAt + 1)
        db.noteDao().update(protected); assertFalse(vm.transformSourceCurrent(fresh)); assertFalse(vm.applyTransform(fresh, "Generated")); assertEquals(protected, db.noteDao().getById(vm.currentNoteId))
        SettingsRepository.get(app).setAiEnabled(false)
        assertTrue(runCatching { vm.captureTransformSource() }.exceptionOrNull() is TransformException)
    }
    @Test fun `input limits do not create an empty row and selected Unicode boundaries remain complete`() = runTest(dispatcher) {
        assertTrue(runCatching { vm.captureTransformSource() }.isFailure); assertEquals(-1L, vm.currentNoteId)
        vm.onContentChange(TextFieldValue("x".repeat(TransformLimits.INPUT + 1)))
        assertEquals(TransformProblem.TOO_LARGE, (runCatching { vm.captureTransformSource() }.exceptionOrNull() as TransformException).problem)
        assertEquals(-1L, vm.currentNoteId)
        vm.onContentChange(TextFieldValue("a😀b", TextRange(2, 3)))
        val source = vm.captureTransformSource(); assertEquals("😀", source.text)
        assertTrue(vm.applyTransform(source, "X")); assertEquals("aXb", vm.contentField.text)
    }
}
