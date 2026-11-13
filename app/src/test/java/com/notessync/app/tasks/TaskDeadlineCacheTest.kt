package com.notessync.app.tasks

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class TaskDeadlineCacheTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() { Dispatchers.setMain(dispatcher); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL) }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `hidden result and next deadline caches clear and reopening cannot replay a now protected record`() = runTest(dispatcher) {
        val db = AppDatabase.get(app); val due = System.currentTimeMillis() + 3_600_000
        val note = NoteEntity(title = "Before background", isTask = true, taskDueAt = due).let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = NotesViewModel(app); models.put("home", vm); vm.selectFilter(NoteFilter.Unfinished)
        val rows = async { vm.results.filterNotNull().first() }
        val next = async { vm.nextTaskDue.filterNotNull().first() }
        advanceTimeBy(301); assertEquals(note.id, rows.await().notes.single().id); assertEquals(due, next.await())
        vm.markListHidden()
        assertNotEquals(vm.listSession.value, vm.results.value?.key?.session)
        withContext(Dispatchers.IO) { withTimeout(5_000) {
            while (vm.results.value != null || vm.nextTaskDue.value != null) delay(10)
        } }
        assertNull(vm.results.value); assertNull(vm.nextTaskDue.value)
        db.noteDao().update(note.copy(isProtected = true))
        val seen = mutableListOf<NotesResult?>()
        val reopened = async { vm.results.onEach { seen.add(it) }.filterNotNull().first() }
        advanceTimeBy(301); assertTrue(reopened.await().notes.isEmpty())
        assertTrue(seen.filterNotNull().all { it.notes.none { item -> item.id == note.id } })
    }
}
