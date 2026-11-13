package com.notessync.app.tasks

import android.app.Application
import android.app.AlarmManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskDeadlineEditorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: NoteEditorViewModel
    @Before fun before() {
        Dispatchers.setMain(dispatcher); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("isTask" to true))); models.put("editor", vm); dispatcher.scheduler.runCurrent()
    }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `date only task persists across blank cleanup and completion without creating a reminder alarm`() = runTest(dispatcher) {
        val due = 1_900_000_000_000L
        vm.chooseTaskDeadline(due); vm.saveNow(force = true)
        val db = AppDatabase.get(app); val id = vm.currentNoteId
        assertEquals(due, db.noteDao().getById(id)!!.taskDueAt); assertNull(vm.reminderAt)
        vm.discardIfEmptyOnExit(); assertFalse(db.noteDao().getById(id)!!.isDeleted)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        val reloaded = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))); models.put("reloaded", reloaded)
        while (!reloaded.isReady) { dispatcher.scheduler.runCurrent(); delay(1) }
        assertEquals(due, reloaded.taskDueAt)
        reloaded.toggleCompleted(); reloaded.saveNow(force = true)
        assertEquals(due, db.noteDao().getById(id)!!.taskDueAt); assertTrue(db.noteDao().getById(id)!!.isCompleted)
    }
    @Test fun `deadline removal preserves content and reminder and a newly protected row rejects a late deadline save`() = runTest(dispatcher) {
        vm.onTitleChange(androidx.compose.ui.text.input.TextFieldValue("Task")); vm.chooseTaskDeadline(1_900_000_000_000L); vm.saveNow(force = true)
        val db = AppDatabase.get(app); val id = vm.currentNoteId
        vm.chooseTaskDeadline(null); vm.saveNow(force = true)
        assertNull(db.noteDao().getById(id)!!.taskDueAt); assertEquals("Task", db.noteDao().getById(id)!!.title)
        val protected = db.noteDao().getById(id)!!.copy(isProtected = true); db.noteDao().update(protected)
        vm.chooseTaskDeadline(1_910_000_000_000L); vm.saveNow(force = true)
        assertEquals(protected, db.noteDao().getById(id))
    }
    @Test fun `database write failure keeps the chosen deadline for retry and does not escape the UI callback`() = runTest(dispatcher) {
        val db = AppDatabase.get(app)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deadline_fail BEFORE INSERT ON notes BEGIN SELECT RAISE(FAIL, 'disk fixture'); END")
        val due = 1_900_000_000_000L
        vm.chooseTaskDeadline(due)
        while (vm.message != com.notessync.app.ui.notes.EditorMessage.SAVE_FAILED) { runCurrent(); delay(1) }
        assertEquals(due, vm.taskDueAt); assertEquals(-1L, vm.currentNoteId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER deadline_fail")
        vm.saveNow(force = true)
        assertEquals(due, db.noteDao().getById(vm.currentNoteId)!!.taskDueAt)
    }

}
