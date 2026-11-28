package com.notessync.app.tasks.running

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
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
class RunningTaskEditorTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun open(note: NoteEntity): NoteEditorViewModel {
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id, "isTask" to true)))
        models.put("editor", vm); withTimeout(15_000) { while (!vm.isReady) delay(5) }; return vm
    }
    @Test fun `typing in an already open editor cannot restart a notification-paused stopwatch or lose the new text`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Task", content = "old", isTask = true, taskTimerStartedAt = now - 5000)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(note)
        assertTrue(RunningTaskActions.apply(app, RunningControl(note.id, note.syncId, RunningClock.STOPWATCH, note.taskTimerStartedAt!!, RunningAction.PAUSE), now))
        vm.onContentChange(TextFieldValue("New content after pause")); assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(note.id)!!
        assertEquals("New content after pause", saved.content); assertEquals(5000L, saved.taskElapsedMillis)
        assertNull(saved.taskTimerStartedAt); assertNull(vm.taskTimerStartedAt); assertEquals(5000L, vm.taskElapsedMillis)
        assertTrue(vm.saveNow(force = true)); assertNull(db.noteDao().getById(note.id)!!.taskTimerStartedAt)
    }
    @Test fun `an editor no-op adopts notification completion and a later text save does not undo it`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Task", content = "old", isTask = true, taskTimerStartedAt = now - 100,
            taskCountdownEndAt = now + 60_000, reminderAt = now + 100_000).let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(note)
        assertTrue(RunningTaskActions.apply(app, RunningControl(note.id, note.syncId, RunningClock.STOPWATCH, note.taskTimerStartedAt!!, RunningAction.COMPLETE), now))
        val completed = db.noteDao().getById(note.id)!!
        assertTrue(vm.saveNow(force = true)); assertTrue(vm.isCompleted); assertNull(vm.taskCountdownEndAt); assertNull(vm.reminderAt)
        vm.onContentChange(TextFieldValue("Text preserved after completion")); assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(note.id)!!; assertTrue(saved.isCompleted); assertEquals(completed.taskCompletedAt, saved.taskCompletedAt)
        assertEquals("Text preserved after completion", saved.content); assertNull(saved.taskTimerStartedAt); assertNull(saved.taskCountdownEndAt)
    }
    @Test fun `open editor follows a notification pause immediately without a text edit or save and keeps its no-op timestamp`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Live pause", content = "Unchanged text", isTask = true,
            taskElapsedMillis = 1000, taskTimerStartedAt = now - 5000).let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(note)
        assertTrue(RunningTaskActions.apply(app, RunningControl(note.id, note.syncId, RunningClock.STOPWATCH, note.taskTimerStartedAt!!, RunningAction.PAUSE), now))
        val committed = db.noteDao().getById(note.id)!!
        withTimeout(15_000) { while (vm.taskTimerStartedAt != null || vm.taskElapsedMillis != committed.taskElapsedMillis) delay(5) }
        assertEquals("Unchanged text", vm.contentField.text)
        assertEquals(committed, db.noteDao().getById(note.id))
        assertTrue(vm.saveNow(force = true))
        assertEquals("A metadata-only adoption must retain a no-op save", committed, db.noteDao().getById(note.id))
    }
    @Test fun `live completion adopts both counters and reminder without losing an unsaved editor draft`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Live complete", content = "Old body", isTask = true,
            taskTimerStartedAt = now - 5000, taskCountdownEndAt = now + 60_000,
            taskCountdownMillis = 60_000, reminderAt = now + 90_000).let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(note); vm.onContentChange(TextFieldValue("Unsaved draft survives completion"))
        assertTrue(RunningTaskActions.apply(app, RunningControl(note.id, note.syncId, RunningClock.STOPWATCH, note.taskTimerStartedAt!!, RunningAction.COMPLETE), now))
        withTimeout(15_000) { while (!vm.isCompleted || vm.taskTimerStartedAt != null || vm.taskCountdownEndAt != null || vm.reminderAt != null) delay(5) }
        assertEquals("Unsaved draft survives completion", vm.contentField.text); assertEquals(now, vm.taskCompletedAt)
        assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(note.id)!!; assertTrue(saved.isCompleted)
        assertEquals("Unsaved draft survives completion", saved.content); assertEquals(now, saved.taskCompletedAt)
        assertNull(saved.taskTimerStartedAt); assertNull(saved.taskCountdownEndAt); assertNull(saved.reminderAt)
    }
    @Test fun `explicit editor resume after a live pause is not reset by the metadata observer baseline`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Resume", isTask = true, taskTimerStartedAt = now - 2000)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(note)
        assertTrue(RunningTaskActions.apply(app, RunningControl(note.id, note.syncId, RunningClock.STOPWATCH, note.taskTimerStartedAt!!, RunningAction.PAUSE), now))
        withTimeout(15_000) { while (vm.taskTimerStartedAt != null) delay(5) }
        vm.toggleTaskTimer(); assertTrue(vm.saveNow(force = true))
        val resumed = db.noteDao().getById(note.id)!!
        assertNotNull("An explicit local start must remain committed", resumed.taskTimerStartedAt)
        assertEquals(resumed.taskTimerStartedAt, vm.taskTimerStartedAt); assertFalse(resumed.isCompleted)
        assertTrue(vm.saveNow(force = true)); assertEquals(resumed, db.noteDao().getById(note.id))
    }

}
