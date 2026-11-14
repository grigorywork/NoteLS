package com.notessync.app.ui.notes.audit

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.tasks.TaskCompletion
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

/** Versions must advance independently of wall-clock skew, while elapsed clocks use the actual event time. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalChangeVersionRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val store = ViewModelStore()
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { store.clear(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `editor edit advances a received future version instead of moving backward`() = runBlocking {
        val previous = System.currentTimeMillis() + 3600000
        val note = NoteEntity(id = 1, title = "Received", content = "Before", updatedAt = previous, dirty = false)
        db.noteDao().upsert(note)
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to 1L))); store.put("editor", vm)
        withTimeout(15000) { while (!vm.isReady) delay(2) }
        vm.onContentChange(TextFieldValue("After")); assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(1)!!
        assertEquals("After", saved.content); assertTrue(saved.dirty)
        assertTrue("A peer with the received version must see the later local edit as newer", saved.updatedAt > previous)
    }
    @Test fun `relation change at same wall time cannot be cleared by older version ACK`() = runBlocking {
        val previous = System.currentTimeMillis() + 3600000
        val note = NoteEntity(id = 1, title = "Snapshot", updatedAt = previous, dirty = false)
        db.noteDao().upsert(note)
        db.noteDao().markContentChanged(1, previous)
        db.noteDao().markVersionSynced(note.syncId, previous)
        val current = db.noteDao().getById(1)!!
        assertTrue("ACK for the old snapshot must not clear a newer relation change", current.dirty)
        assertTrue(current.updatedAt > previous)
    }
    @Test fun `completion advances version but elapsed time uses actual event wall clock`() {
        val now = System.currentTimeMillis(); val previous = now + 3600000
        val note = NoteEntity(isTask = true, updatedAt = previous, taskTimerStartedAt = now - 1000,
            taskCountdownMillis = 10000, taskCountdownEndAt = now + 9000)
        val done = TaskCompletion.toggle(note, now)
        assertTrue(done.isCompleted); assertEquals(1000L, done.taskElapsedMillis)
        assertEquals(9000L, done.taskCountdownMillis); assertEquals(now, done.taskCompletedAt)
        assertTrue("Synchronization version must not move backward on completion", done.updatedAt > previous)
    }
    @Test fun `matching current ACK still clears note and notebook dirty flags`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Pending", updatedAt = 123, dirty = true)
        db.noteDao().upsert(note); db.noteDao().markVersionSynced(note.syncId, 123)
        assertFalse(db.noteDao().getById(1)!!.dirty)
        val book = com.notessync.app.data.local.entity.NotebookEntity(id = 1, name = "Pending", updatedAt = 123, dirty = true)
        db.notebookDao().upsert(book); db.notebookDao().markVersionSynced(book.syncId, 123)
        assertFalse(db.notebookDao().getById(1)!!.dirty)
    }
    @Test fun `SQL delete restore and notebook detach advance future versions without extending clocks`() = runBlocking {
        val now = System.currentTimeMillis(); val previous = now + 3600000
        val bookId = db.notebookDao().upsert(com.notessync.app.data.local.entity.NotebookEntity(id = 1, name = "Book", updatedAt = previous, dirty = false))
        val note = NoteEntity(id = 1, title = "Future", updatedAt = previous, notebookId = bookId,
            isTask = true, taskTimerStartedAt = now - 1000, taskCountdownEndAt = now + 9000)
        db.noteDao().upsert(note); db.noteDao().softDelete(1, now)
        val deleted = db.noteDao().getById(1)!!
        assertTrue(deleted.updatedAt > previous); assertEquals(1000L, deleted.taskElapsedMillis)
        assertEquals(9000L, deleted.taskCountdownMillis)
        db.noteDao().restore(1, now); val restored = db.noteDao().getById(1)!!
        assertFalse(restored.isDeleted); assertTrue(restored.updatedAt > deleted.updatedAt)
        db.noteDao().clearNotebook(bookId, now); val detached = db.noteDao().getById(1)!!
        assertNull(detached.notebookId); assertTrue(detached.updatedAt > restored.updatedAt)
        db.notebookDao().softDelete(bookId, now)
        assertTrue(db.notebookDao().getById(bookId)!!.updatedAt > previous)
    }
    @Test fun `batch trash pauses at wall clock rather than future synchronization version`() = runBlocking {
        val now = System.currentTimeMillis(); val start = now - 1000
        val note = NoteEntity(id = 1, title = "Future", updatedAt = now + 3600000, isTask = true, taskTimerStartedAt = start)
        db.noteDao().upsert(note)
        assertEquals(1, com.notessync.app.notes.actions.NoteActions(app, db).apply(setOf(1L), com.notessync.app.notes.actions.NoteActions.Action.TRASH))
        val after = System.currentTimeMillis(); val saved = db.noteDao().getById(1)!!
        assertTrue(saved.updatedAt > note.updatedAt)
        assertTrue(saved.taskElapsedMillis >= 1000)
        assertTrue("Future logical version must not add artificial elapsed time", saved.taskElapsedMillis <= (after - start).coerceAtLeast(0))
    }

}
