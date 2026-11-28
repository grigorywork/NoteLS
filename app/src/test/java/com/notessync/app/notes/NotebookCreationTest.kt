package com.notessync.app.notes

import android.app.Application
import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
import com.notessync.app.ui.navigation.Routes
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class NotebookCreationTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private var sequence = 0
    @Before fun before() {
        Dispatchers.setMain(dispatcher); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        db = AppDatabase.get(app); ProtectionSession.lockAll()
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); AppDatabase.closeInstance(); ProtectionSession.lockAll(); Dispatchers.resetMain() }
    private suspend fun editor(book: NotebookEntity? = null, task: Boolean = false, id: Long = -1,
        identity: String = book?.syncId.orEmpty()): NoteEditorViewModel {
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id, "isTask" to task,
            "notebookId" to (book?.id ?: -1L), "notebookSyncId" to identity)))
        models.put("editor-${sequence++}", vm)
        while (!vm.isReady && !vm.accessDenied) { dispatcher.scheduler.runCurrent(); delay(1) }
        return vm
    }
    private suspend fun book(name: String) = NotebookEntity(name = name).let { it.copy(id = db.notebookDao().upsert(it)) }
    private suspend fun noteCount(): Int = db.noteDao().observeAll().first().size

    @Test fun `notes and tasks retain selected notebook through save reload completion and archive views`() = runTest(dispatcher) {
        val work = book("Work")
        val unrelated = NoteEntity(title = "Old unfiled", content = "Never guess its destination").let { it.copy(id = db.noteDao().upsert(it)) }
        for (task in listOf(false, true)) {
            val vm = editor(work, task); assertEquals("Work", vm.notebookName)
            vm.onTitleChange(TextFieldValue("Created $task")); vm.onContentChange(TextFieldValue("Body $task"))
            assertTrue(vm.saveNow(force = true))
            val saved = db.noteDao().getById(vm.currentNoteId)!!
            assertEquals(work.id, saved.notebookId); assertEquals("Body $task", saved.content)
            models.clear(); dispatcher.scheduler.runCurrent()
            val reloaded = editor(id = saved.id); assertEquals("Work", reloaded.notebookName); assertEquals(saved.content, reloaded.contentField.text)
            if (task) { reloaded.toggleCompleted(); assertTrue(reloaded.saveNow(force = true)) }
            val after = db.noteDao().getById(saved.id)!!
            assertEquals(work.id, after.notebookId); assertEquals(saved.createdAt, after.createdAt); assertEquals(saved.syncId, after.syncId)
            if (task) {
                assertTrue(after.isCompleted)
                assertEquals(listOf(saved.id), db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.Completed, NoteSort.BY_DATE, archiveCompleted = true)).first().map { it.id })
                assertFalse(db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.Notebook(work.id, work.name), NoteSort.BY_DATE, archiveCompleted = true)).first().any { it.id == saved.id })
            }
            models.clear(); dispatcher.scheduler.runCurrent()
        }
        assertEquals(3, noteCount()); assertEquals(unrelated, db.noteDao().getById(unrelated.id))
        assertEquals(2, db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.Notebook(work.id, work.name), NoteSort.BY_DATE)).first().size)
    }

    @Test fun `invalid deleted diary locked and reused notebook routes never fall back to unfiled`() = runTest(dispatcher) {
        val original = book("Work")
        val variants = listOf(original.copy(isDeleted = true), original.copy(isDiary = true),
            original.copy(isProtected = true), original.copy(syncId = "replacement"))
        for (changed in variants) {
            db.notebookDao().update(changed)
            val vm = editor(original); assertTrue(vm.accessDenied); assertFalse(vm.saveNow(force = true))
            assertEquals(changed, db.notebookDao().getById(original.id)); assertEquals(0, noteCount())
        }
        db.notebookDao().update(original)
        for ((target, identity) in listOf(original.copy(id = original.id + 100) to original.syncId, original to "", original to "wrong")) {
            val vm = editor(target, identity = identity); assertTrue(vm.accessDenied); assertFalse(vm.saveNow(force = true))
        }
        assertEquals(0, noteCount())
    }

    @Test fun `a destination changed after draft loading is checked inside the insert transaction`() = runTest(dispatcher) {
        val original = book("Work")
        for (changed in listOf(original.copy(isDeleted = true), original.copy(isProtected = true),
                original.copy(isDiary = true), original.copy(syncId = "replacement"))) {
            db.notebookDao().update(original)
            val vm = editor(original); vm.onContentChange(TextFieldValue("Pending"))
            db.notebookDao().update(changed)
            assertFalse(vm.saveNow(force = true)); assertTrue(vm.accessDenied)
            assertEquals(-1L, vm.currentNoteId); assertEquals(0, noteCount()); assertEquals(changed, db.notebookDao().getById(original.id))
        }
    }

    @Test fun `an unlocked private notebook accepts creation and relocking rejects a stale write`() = runTest(dispatcher) {
        val original = book("Private").copy(isProtected = true)
        db.notebookDao().update(original); ProtectionSession.unlockNotebook(original.id)
        val vm = editor(original); vm.onContentChange(TextFieldValue("Saved safely")); assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(vm.currentNoteId)!!; assertEquals(original.id, saved.notebookId)
        ProtectionSession.lockNotebook(original.id); vm.onContentChange(TextFieldValue("Late write"))
        assertFalse(vm.saveNow(force = true)); assertEquals(saved, db.noteDao().getById(saved.id))
        val locked = editor(id = saved.id); assertTrue(locked.accessDenied)
        ProtectionSession.unlockNotebook(original.id)
        assertEquals("Saved safely", editor(id = saved.id).contentField.text)
    }

    @Test fun `existing note ignores creation arguments and stale explicit destination cannot bypass protection`() = runTest(dispatcher) {
        val work = book("Work"); val personal = book("Personal")
        val source = NoteEntity(title = "Existing", notebookId = work.id).let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = editor(personal, id = source.id)
        assertEquals("Work", vm.notebookName); assertTrue(vm.saveNow(force = true)); assertEquals(source, db.noteDao().getById(source.id))
        db.notebookDao().update(personal.copy(isProtected = true)); vm.assignNotebook(personal)
        dispatcher.scheduler.runCurrent(); assertFalse(vm.saveNow(force = true)); assertEquals(source, db.noteDao().getById(source.id))
        val clear = editor(id = source.id); clear.assignNotebook(null); assertTrue(clear.saveNow(force = true))
        val after = db.noteDao().getById(source.id)!!
        assertNull(after.notebookId); assertEquals(source.syncId, after.syncId); assertEquals(source.createdAt, after.createdAt)
    }

    @Test fun `external notebook moves and deletion clearing are not undone by an old editor snapshot`() = runTest(dispatcher) {
        val work = book("Work"); val personal = book("Personal")
        val source = NoteEntity(content = "Original", notebookId = work.id).let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = editor(id = source.id)
        db.noteDao().update(source.copy(notebookId = personal.id))
        vm.onContentChange(TextFieldValue("Edited")); assertTrue(vm.saveNow(force = true))
        assertEquals(personal.id, db.noteDao().getById(source.id)!!.notebookId); assertEquals("Personal", vm.notebookName)
        val moved = db.noteDao().getById(source.id)!!
        db.noteDao().update(moved.copy(notebookId = null)); db.notebookDao().update(personal.copy(isDeleted = true))
        vm.onContentChange(TextFieldValue("Edited again")); assertTrue(vm.saveNow(force = true))
        val after = db.noteDao().getById(source.id)!!
        assertNull(after.notebookId); assertNull(vm.notebookName); assertEquals("Edited again", after.content)
        assertEquals(source.createdAt, after.createdAt); assertTrue(db.notebookDao().getById(personal.id)!!.isDeleted)
    }

    @Test fun `legacy routes stay byte compatible while notebook identities are URI encoded`() {
        assertEquals("editor?noteId=42&isTask=true&dayKey=7", Routes.editor(42, true, 7))
        val identity = "work /?#&=☑"
        val uri = Uri.parse(Routes.editor(isTask = true, notebookId = 9, notebookSyncId = identity))
        assertEquals("9", uri.getQueryParameter("notebookId")); assertEquals(identity, uri.getQueryParameter("notebookSyncId"))
        assertEquals("true", uri.getQueryParameter("isTask")); assertEquals("-1", uri.getQueryParameter("dayKey"))
    }

    @Test fun `failed first insert keeps its destination for retry and a renamed notebook is accepted`() = runTest(dispatcher) {
        val work = book("Work"); val vm = editor(work)
        vm.onContentChange(TextFieldValue("Keep this draft"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER notebook_insert_fail BEFORE INSERT ON notes BEGIN SELECT RAISE(FAIL, 'disk fixture'); END")
        assertTrue(runCatching { vm.saveNow(force = true) }.isFailure)
        assertEquals(-1L, vm.currentNoteId); assertEquals(0, noteCount())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER notebook_insert_fail")
        db.notebookDao().update(work.copy(name = "Renamed"))
        assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(vm.currentNoteId)!!
        assertEquals(work.id, saved.notebookId); assertEquals("Keep this draft", saved.content); assertEquals("Renamed", vm.notebookName)
    }
}
