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
class LocalChangeVersionAuditProbe {
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
}
