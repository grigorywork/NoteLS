package com.notessync.app.notes.audit

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.model.NoteCardEntry
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
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
import java.util.UUID

/** Cached card commands across a changed DB identity/state; actual Room/VM, not a physical restore gesture. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StaleCardActionsRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    private lateinit var note: NoteEntity
    private lateinit var card: NoteCardEntry
    private lateinit var vm: NotesViewModel
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        ProtectionSession.lockAll(); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        note = NoteEntity(title = "Original task", isTask = true, dirty = false).let { it.copy(id = db.noteDao().upsert(it)) }
        card = db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.All, NoteSort.BY_DATE)).first().single()
        vm = NotesViewModel(app); models.put("home", vm)
    }
    @After fun after() { models.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun command(action: () -> Unit) {
        val parent = vm.viewModelScope.coroutineContext[Job]!!; val existing = parent.children.toSet()
        action(); withTimeout(15000) { parent.children.filter { it !in existing }.toList().joinAll() }
    }
    @Test fun `stale card cannot complete another restored identity under the same numeric ID`() = runBlocking {
        val restored = note.copy(syncId = UUID.randomUUID().toString(), title = "Restored task")
        db.noteDao().update(restored)
        command { vm.toggleCompleted(card) }
        assertFalse("A stale card must not complete a different identity", db.noteDao().getById(note.id)!!.isCompleted)
    }
    @Test fun `stale delete candidate cannot trash a different restored identity`() = runBlocking {
        val restored = note.copy(syncId = UUID.randomUUID().toString(), title = "Restored task")
        db.noteDao().update(restored)
        assertEquals("A stale delete candidate must match the global identity", 0, vm.delete(card))
        assertFalse(db.noteDao().getById(note.id)!!.isDeleted)
    }
    @Test fun `stale mark done action cannot undo a more recent completion of the same identity`() = runBlocking {
        db.noteDao().update(note.copy(isCompleted = true, taskCompletedAt = System.currentTimeMillis(), updatedAt = note.updatedAt + 1))
        command { vm.toggleCompleted(card) }
        assertTrue("The old unchecked card expresses mark done, not undo a newer completion", db.noteDao().getById(note.id)!!.isCompleted)
    }
    @Test fun `stale start action cannot pause a clock already started by another writer`() = runBlocking {
        val started = System.currentTimeMillis() - 1000
        db.noteDao().update(note.copy(taskTimerStartedAt = started, updatedAt = note.updatedAt + 1))
        command { vm.toggleTaskTimer(card) }
        assertEquals("The old stopped card must not pause an already running clock", started, db.noteDao().getById(note.id)!!.taskTimerStartedAt)
    }
}
