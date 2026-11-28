package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.subtasks.replaceSubtasks
import com.notessync.app.notes.subtasks.Subtask
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
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
@Config(sdk=[28], application=Application::class, manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorDatesPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        ProtectionSession.lockAll(); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(15_000) { while (!condition()) delay(5) } }
    private suspend fun open(id: Long = -1): NoteEditorViewModel = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id)))
        .also { models.put("editor", it); waitFor { it.isReady }; if (id > 0) waitFor { it.editorDates != null } }
    private suspend fun insert(note: NoteEntity) = note.copy(id = db.noteDao().upsert(note))
    @Test fun `new dates appear only after child and parent commit and no-op save leaves them unchanged`() = runBlocking {
        val vm = open(); assertNull(vm.editorDates)
        val child = vm.addSubtask()!!; vm.editSubtask(child, "Pending child")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_child BEFORE INSERT ON note_subtasks BEGIN SELECT RAISE(ABORT, 'dates-fixture'); END")
        assertTrue(runCatching { vm.saveNow(true) }.isFailure); assertNull(vm.editorDates); assertEquals(-1L, vm.currentNoteId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_child")
        assertTrue(vm.saveNow(true)); waitFor { vm.editorDates != null }
        val saved = db.noteDao().getById(vm.currentNoteId)!!
        assertEquals(saved.createdAt, vm.editorDates!!.createdAt); assertEquals(saved.updatedAt, vm.editorDates!!.updatedAt)
        assertTrue(vm.saveNow(true)); delay(30)
        assertEquals(saved.updatedAt, db.noteDao().getById(saved.id)!!.updatedAt)
        assertEquals(saved.updatedAt, vm.editorDates!!.updatedAt)
    }
    @Test fun `failed existing save leaves visible dates unchanged until retry commits both documents`() = runBlocking {
        val note = insert(NoteEntity(content="Original", createdAt=1_700_000_000_000, updatedAt=1_700_000_001_000))
        db.withTransaction { replaceSubtasks(db, note.id, listOf(Subtask("child", "Old"))) }
        val vm = open(note.id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_update BEFORE INSERT ON note_subtasks BEGIN SELECT RAISE(ABORT, 'dates-fixture'); END")
        vm.onContentChange(TextFieldValue("Pending")); vm.editSubtask("child", "New")
        assertTrue(runCatching { vm.saveNow(true) }.isFailure)
        assertEquals(note.updatedAt, vm.editorDates!!.updatedAt); assertEquals(note, db.noteDao().getById(note.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_update")
        assertTrue(vm.saveNow(true)); val saved = db.noteDao().getById(note.id)!!
        waitFor { vm.editorDates?.updatedAt == saved.updatedAt }
        assertTrue(saved.updatedAt > note.updatedAt); assertEquals(note.createdAt, vm.editorDates!!.createdAt)
    }
    @Test fun `committed peer dates refresh while local text remains and save preserves fresh creation date`() = runBlocking {
        val note = insert(NoteEntity(content="Original", createdAt=1_700_000_000_000, updatedAt=1_700_000_001_000))
        val vm = open(note.id)
        val peer = note.copy(createdAt=note.createdAt-86_400_000, updatedAt=note.updatedAt+1000)
        db.noteDao().update(peer); waitFor { vm.editorDates?.createdAt == peer.createdAt }
        assertEquals(peer.updatedAt, vm.editorDates!!.updatedAt); assertEquals("Original", vm.contentField.text)
        vm.onContentChange(TextFieldValue("Local edit")); assertTrue(vm.saveNow(true))
        assertEquals(peer.createdAt, db.noteDao().getById(note.id)!!.createdAt)
    }
    @Test fun `lock and same-id replacement cannot expose dates under stale editor identity`() = runBlocking {
        val note = insert(NoteEntity(content="Private", isProtected=true))
        ProtectionSession.unlockTask(note.syncId); val vm = open(note.id)
        ProtectionSession.lockTask(note.syncId); waitFor { vm.editorDates == null }
        ProtectionSession.unlockTask(note.syncId); waitFor { vm.editorDates != null }
        db.noteDao().update(note.copy(syncId="replacement", isProtected=false)); waitFor { vm.editorDates == null }
        assertEquals("Private", vm.contentField.text)
    }
    @Test fun `notebook deletion and protection revoke ordinary note dates live`() = runBlocking {
        val book = NotebookEntity(name="Book").let { it.copy(id=db.notebookDao().upsert(it)) }
        val note = insert(NoteEntity(content="Body", notebookId=book.id))
        val vm = open(note.id)
        db.notebookDao().softDelete(book.id)
        assertEquals(book.id, db.noteDao().getById(note.id)!!.notebookId)
        waitFor { vm.editorDates == null }
        db.notebookDao().update(book); waitFor { vm.editorDates != null }
        db.notebookDao().update(book.copy(isProtected=true))
        assertEquals(book.id, db.noteDao().getById(note.id)!!.notebookId)
        waitFor { vm.editorDates == null }
        ProtectionSession.unlockNotebook(book.id); waitFor { vm.editorDates != null }
        db.noteDao().update(note.copy(isDeleted=true)); waitFor { vm.editorDates == null }
    }
}
