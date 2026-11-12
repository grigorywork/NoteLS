package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.notes.text.*
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.md.MarkdownEdit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real SQLite under Robolectric; no claims about hardware keyboard, PSS or battery. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NoteEditorHistoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var app: Application
    private lateinit var vm: NoteEditorViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setEditorHistory(true)
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        vm = NoteEditorViewModel(app, SavedStateHandle())
        store.put("editor", vm)
        dispatcher.scheduler.runCurrent()
    }
    @After fun tearDown() {
        store.clear(); dispatcher.scheduler.runCurrent()
        AppDatabase.closeInstance(); Dispatchers.resetMain()
    }
    private fun text(value: String, start: Int = value.length, end: Int = start) {
        vm.onContentChange(TextFieldValue(value, TextRange(start, end)))
    }

    @Test fun `formatting and title undo roundtrip save and reopen`() = runTest(dispatcher) {
        text("Привет 😀"); vm.selectAllContent(); vm.toggleBold()
        val bold = vm.formatting
        vm.onTitleChange(TextFieldValue("Title", TextRange(5)))
        vm.undo(); assertEquals("", vm.title); assertEquals(bold, vm.formatting)
        vm.undo(); assertTrue(vm.formatting.marks.isEmpty()); assertEquals("Привет 😀", vm.contentField.text)
        vm.redo(); vm.redo(); vm.flushOnExit()
        val saved = AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertEquals("Title", saved.title); assertEquals("Привет 😀", saved.content)
        assertEquals(bold, TextFormatting.decode(saved.textFormat, saved.content.length))
        assertFalse(vm.canUndo); assertFalse(vm.canRedo)
        val reopened = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to saved.id)))
        store.put("reopened", reopened)
        // Await actual Room IO, not a simulated completion of the load.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!reopened.isReady) delay(10) }
        }
        assertEquals(saved.content, reopened.contentField.text)
        assertFalse(reopened.canUndo); assertFalse(reopened.canRedo)
    }

    @Test fun `IME replacement undo never restores stale composition`() = runTest(dispatcher) {
        vm.onContentChange(TextFieldValue("ni", TextRange(2), TextRange(0, 2)))
        vm.onContentChange(TextFieldValue("你", TextRange(1), TextRange(0, 1)))
        vm.onContentChange(TextFieldValue("你", TextRange(1)))
        vm.undo(); assertEquals("", vm.contentField.text); assertNull(vm.contentField.composition)
        vm.redo(); assertEquals("你", vm.contentField.text); assertNull(vm.contentField.composition)
    }

    @Test fun `single character clipboard paste and following typing are separate steps`() = runTest(dispatcher) {
        text("a")
        vm.prepareClipboardEdit(); text("ab")
        text("abc")
        vm.undo(); assertEquals("ab", vm.contentField.text)
        vm.undo(); assertEquals("a", vm.contentField.text)
    }

    @Test fun `pending formatting, selection and branch work together`() = runTest(dispatcher) {
        vm.toggleBold(); assertTrue(vm.canUndo)
        text("x"); assertEquals("1", vm.formatting.at(0)[FormatKind.BOLD])
        vm.undo(); assertEquals("", vm.contentField.text); assertEquals("1", vm.activeFormatting[FormatKind.BOLD])
        vm.undo(); assertNull(vm.activeFormatting[FormatKind.BOLD])
        vm.redo(); text("y"); assertFalse(vm.canRedo)
        vm.selectAllContent(); vm.formatSelection(FormatKind.COLOR, "FFE53935")
        vm.undo(); assertEquals(TextRange(0, 1), vm.contentField.selection)
        assertNull(vm.formatting.at(0)[FormatKind.COLOR])
    }

    @Test fun `lists Enter paragraph checkbox and voice insert are atomic edits`() = runTest(dispatcher) {
        text("first\nsecond"); vm.selectAllContent()
        vm.toggleList(MarkdownEdit.ListKind.NUMBER)
        val numbered = vm.contentField.text
        assertTrue(numbered.startsWith("1.")); vm.undo(); assertEquals("first\nsecond", vm.contentField.text)
        vm.redo(); assertEquals(numbered, vm.contentField.text)
        vm.toggleChecklist(); val checklist = vm.contentField.text
        vm.toggleCheckboxAtLine(0); assertNotEquals(checklist, vm.contentField.text)
        vm.undo(); assertEquals(checklist, vm.contentField.text)
        vm.formatParagraph(FormatKind.ALIGN, "CENTER"); assertTrue(vm.formatting.marks.isNotEmpty())
        vm.undo(); assertTrue(vm.formatting.marks.isEmpty())
        text(vm.contentField.text)
        val beforeEnter = vm.contentField
        text(beforeEnter.text + "\n"); assertTrue(vm.contentField.text.endsWith("- [ ] "))
        vm.undo(); assertEquals(beforeEnter.text, vm.contentField.text)
        val beforeVoice = vm.contentField
        vm.insertVoiceText("слова голосом", VoiceStructure.RAW)
        vm.undo(); assertEquals(beforeVoice, vm.contentField)
    }

    @Test fun `disabling history releases redo and still saves edits`() = runTest(dispatcher) {
        text("one"); vm.undo(); assertTrue(vm.canRedo)
        SettingsRepository.get(app).setEditorHistory(false); runCurrent()
        assertFalse(vm.historyEnabled); assertFalse(vm.canRedo); assertFalse(vm.canUndo)
        text("two"); assertFalse(vm.canUndo)
        vm.saveNow(); assertEquals("two", AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!.content)
        SettingsRepository.get(app).setEditorHistory(true); runCurrent()
        text("two!"); vm.undo(); assertEquals("two", vm.contentField.text)
    }

    @Test fun `older in-flight save cannot overwrite undo or create a second note`() = runTest(dispatcher) {
        val db = AppDatabase.get(app)
        db.openHelper.writableDatabase
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        db.transactionExecutor.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            text("before")
            val first = async { vm.saveNow() }
            runCurrent()
            vm.undo(); text("after"); vm.selectAllContent(); vm.toggleItalic()
            val last = async { vm.saveNow() }
            runCurrent()
            first.cancel()
            release.countDown()
            joinAll(first, last)
            val notes = db.noteDao().getDirty()
            assertEquals(1, notes.size); assertEquals(vm.currentNoteId, notes.single().id)
            assertEquals("after", notes.single().content)
            assertEquals(vm.formatting.encode(), notes.single().textFormat)
        } finally { release.countDown() }
    }

    @Test fun `failed final save keeps editor and undo available until retry`() = runTest(dispatcher) {
        text("saved"); vm.saveNow()
        val db = AppDatabase.get(app)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_editor_update BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        text("unsaved")
        assertFalse(vm.flushOnExit())
        assertFalse(vm.isClosing); assertEquals(EditorMessage.SAVE_FAILED, vm.message)
        assertTrue(vm.canUndo); assertEquals("unsaved", vm.contentField.text)
        vm.undo(); assertEquals("saved", vm.contentField.text)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_editor_update")
        assertTrue(vm.flushOnExit())
        assertEquals("saved", db.noteDao().getById(vm.currentNoteId)!!.content)
    }

    @Test fun `debounced save plus immediate undo and exit keeps final content`() = runTest(dispatcher) {
        text("kept"); vm.saveNow()
        text("temporary"); Snapshot.sendApplyNotifications(); runCurrent()
        advanceTimeBy(501); runCurrent()
        vm.undo(); vm.flushOnExit()
        val saved = AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertEquals("kept", saved.content)
        assertEquals("", saved.textFormat)
    }
}
