package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.notes.text.*
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.photos.PhotoDisplaySize
import com.notessync.app.data.local.entity.AttachmentEntity
import java.io.File
import com.notessync.app.data.local.entity.NoteEntity
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

    @Test fun `editor preserves fresh pin and cannot autosave a trashed or purged note`() = runTest(dispatcher) {
        text("Original"); vm.saveNow()
        val db = AppDatabase.get(app); val id = vm.currentNoteId
        val actions = com.notessync.app.notes.actions.NoteActions(app, db)
        actions.apply(setOf(id), com.notessync.app.notes.actions.NoteActions.Action.PIN)
        text("Latest"); vm.chooseTaskColor("mint"); vm.saveNow()
        assertTrue(db.noteDao().getById(id)!!.isPinned)
        assertEquals("mint", db.noteDao().getById(id)!!.noteColor)
        actions.apply(setOf(id), com.notessync.app.notes.actions.NoteActions.Action.TRASH)
        text("Late autosave"); vm.saveNow(force = true)
        assertTrue(db.noteDao().getById(id)!!.isDeleted)
        assertEquals("Latest", db.noteDao().getById(id)!!.content)
        actions.apply(setOf(id), com.notessync.app.notes.actions.NoteActions.Action.PURGE)
        vm.saveNow(force = true)
        assertTrue(db.noteDao().getById(id)!!.isPurged); assertEquals("", db.noteDao().getById(id)!!.content)
    }

    @Test fun `photo size save and note save preserve bytes and reject stale ownership`() = runTest(dispatcher) {
        text("Photo note"); vm.saveNow()
        val db = AppDatabase.get(app)
        val file = File(app.filesDir, "photo-size-original").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = db.attachmentDao().upsert(AttachmentEntity(noteId = vm.currentNoteId,
            filePath = file.path, mimeType = "image/jpeg", sizeBytes = 3))
        val photo = db.attachmentDao().getByNoteId(vm.currentNoteId).single()
        text("New body")
        val size = async { vm.savePhotoSize(photo, PhotoDisplaySize(480, 360)) }
        val saved = async { vm.saveNow() }
        assertTrue(size.await()); saved.await()
        val result = db.attachmentDao().getBySyncId(photo.syncId)!!
        assertEquals(id, result.id); assertEquals(480, result.displayWidthDp); assertEquals(360, result.displayHeightDp)
        assertEquals("New body", db.noteDao().getById(vm.currentNoteId)!!.content)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertFalse(vm.savePhotoSize(photo.copy(noteId = vm.currentNoteId + 1), PhotoDisplaySize()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_size BEFORE UPDATE ON attachments BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertFalse(vm.savePhotoSize(photo, PhotoDisplaySize(300, 200)))
        assertEquals(EditorMessage.PHOTO_SIZE_FAILED, vm.message)
        assertEquals(480, db.attachmentDao().getBySyncId(photo.syncId)!!.displayWidthDp)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_size")
        assertTrue(vm.savePhotoSize(photo, PhotoDisplaySize(300, 200)))
    }

    @Test fun `table-only note saves and undo redo includes cells dimensions and deletion`() = runTest(dispatcher) {
        val id = vm.addTable(2, 2)!!
        val ref = TableCellRef(id, 0, 1)
        vm.editTableCell(ref, "a"); vm.editTableCell(ref, "ab")
        vm.undo(); assertEquals("", vm.tables!!.find(id)!!.cells[0][1])
        vm.redo(); assertEquals("ab", vm.tables!!.find(id)!!.cells[0][1])
        vm.resizeTableColumn(id, 1, 300); vm.undo(); assertEquals(160, vm.tables!!.find(id)!!.columnWidths[1])
        vm.redo(); vm.resizeTableRow(id, 0, 144)
        vm.removeTable(id); assertFalse(vm.hasTables); vm.undo(); assertTrue(vm.hasTables)
        val expected = vm.tables!!.encode()
        assertTrue(vm.flushOnExit())
        val note = AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertFalse(note.isDeleted); assertEquals("", note.content); assertEquals(expected, note.tablesData)
    }
    @Test fun `table caret moves split input groups and IME undo clears composition`() = runTest(dispatcher) {
        val id = vm.addTable(1, 1)!!; val ref = TableCellRef(id, 0, 0)
        vm.editTableCell(ref, "ab")
        vm.onTableCellChange(ref, TextFieldValue("ab", TextRange(2)), TextFieldValue("abc", TextRange(3)))
        vm.onTableCellChange(ref, TextFieldValue("abc", TextRange(3)), TextFieldValue("abc", TextRange(0)))
        vm.onTableCellChange(ref, TextFieldValue("abc", TextRange(0)), TextFieldValue("xabc", TextRange(1)))
        vm.undo(); assertEquals("abc", vm.tables!!.find(id)!!.cells[0][0])
        assertEquals(com.notessync.app.notes.history.EditorSelection(0), vm.focusedTableSelection)
        vm.undo(); assertEquals("ab", vm.tables!!.find(id)!!.cells[0][0])
        vm.onTableCellChange(ref, TextFieldValue("ab", TextRange(2)), TextFieldValue("abni", TextRange(4), TextRange(2, 4)))
        vm.onTableCellChange(ref, TextFieldValue("abni", TextRange(4), TextRange(2, 4)), TextFieldValue("ab你", TextRange(3)))
        vm.undo(); assertEquals("ab", vm.tables!!.find(id)!!.cells[0][0])
    }

    @Test fun `unsupported table bytes survive text edits and undo without data loss`() = runTest(dispatcher) {
        val raw = "{\"version\":99,\"blocks\":[\"future\"]}"
        val id = AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "future", tablesData = raw))
        val other = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id)))
        store.put("other", other)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (!other.isReady) delay(10) } }
        assertNull(other.tables); assertTrue(other.hasTables); assertNull(other.addTable(1, 1))
        other.onContentChange(TextFieldValue("changed")); other.undo(); other.redo()
        assertTrue(other.flushOnExit())
        val note = AppDatabase.get(app).noteDao().getById(id)!!
        assertEquals(raw, note.tablesData); assertEquals("changed", note.content)
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
    @Test fun `dictation inserts at the caret without joining the following word and restores body history`() = runTest(dispatcher) {
        text("НачалоКонец")
        vm.onContentChange(TextFieldValue("НачалоКонец", TextRange(6)))
        vm.onEditorFocus(com.notessync.app.notes.history.EditorField.TITLE)
        vm.insertVoiceText("Фраза.", VoiceStructure.RAW)
        assertEquals("Начало Фраза. Конец", vm.contentField.text)
        assertEquals(com.notessync.app.notes.history.EditorField.CONTENT, vm.editorField)
        vm.undo(); assertEquals("НачалоКонец", vm.contentField.text); assertEquals(TextRange(6), vm.contentField.selection)
        vm.redo(); assertEquals("Начало Фраза. Конец", vm.contentField.text)
    }
    @Test fun `whole cell styles undo redo save and reopen without changing note formatting`() = runTest(dispatcher) {
        text("Body")
        val id=vm.addTable(2,2)!!;val ref=TableCellRef(id,0,1)
        vm.editTableCell(ref,"Ячейка 😀")
        val before=vm.tables
        vm.formatTableCells(ref,TableFormatScope.ROW,FormatKind.FONT,"MONO")
        val styled=vm.tables
        vm.undo();assertEquals(before,vm.tables);vm.redo();assertEquals(styled,vm.tables)
        assertTrue(vm.flushOnExit())
        val saved=AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertEquals(styled,NoteTables.decode(saved.tablesData));assertEquals("Body",saved.content)
        assertEquals("",saved.textFormat)
        val other=NoteEditorViewModel(app,SavedStateHandle(mapOf("noteId" to saved.id)))
        store.put("other",other)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while(!other.isReady) delay(10) } }
        assertEquals(styled,other.tables)
    }
    @Test fun `relocked note rejects style persistence and subsequent style actions`() = runTest(dispatcher) {
        val id=vm.addTable(1,1)!!;val ref=TableCellRef(id,0,0)
        vm.editTableCell(ref,"Secret");assertTrue(vm.saveNow())
        val dao=AppDatabase.get(app).noteDao();val saved=dao.getById(vm.currentNoteId)!!
        dao.update(saved.copy(isProtected=true))
        vm.formatTableCells(ref,TableFormatScope.TABLE,FormatKind.COLOR,"FFE53935")
        assertFalse(vm.saveNow());assertTrue(vm.accessDenied)
        assertEquals(saved.tablesData,dao.getById(saved.id)!!.tablesData)
        val denied=vm.tables
        vm.clearTableStyles(ref,TableFormatScope.TABLE);vm.formatTableCells(ref,TableFormatScope.CELL,FormatKind.BOLD,"1")
        assertEquals(denied,vm.tables)
    }

}
