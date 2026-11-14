package com.notessync.app.ui.notes.audit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.PasswordRecord
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

/** Exact queue ordering, not a slow-phone timing simulation or a sleep-based cryptography benchmark. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EmptyProtectionExitAuditProbe {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var vm: NoteEditorViewModel
    @Before fun before() {
        Dispatchers.setMain(dispatcher); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("isTask" to true)))
        store.put("editor", vm); dispatcher.scheduler.runCurrent(); assertTrue(vm.isReady)
    }
    @After fun after() { store.clear(); dispatcher.scheduler.runCurrent(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `blank editor exit waits for accepted protection before navigation can cancel its owner`() = runTest(dispatcher) {
        val field = NoteEditorViewModel::class.java.getDeclaredField("saveMutex").apply { isAccessible = true }
        val mutex = field.get(vm) as Mutex
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val password = "PUBLIC SYNTHETIC OWNER PASSWORD"
        val due = System.currentTimeMillis() + 3600000
        mutex.lock()
        var initialHeld = true
        var blocker: Job? = null
        var exit: Deferred<Boolean>? = null
        try {
            vm.protectTask(password); runCurrent() // Protection flush is the first queued owner.
            assertTrue(vm.taskProtectionBusy); assertEquals(-1L, vm.currentNoteId)
            vm.chooseTaskDeadline(due)
            exit = async { vm.flushOnExit() }; runCurrent()
            // Old exit queues empty cleanup before protection commit. A correct exit awaits protection first.
            blocker = launch { mutex.withLock { entered.complete(Unit); release.await() } }
            runCurrent(); initialHeld = false; mutex.unlock()
            withContext(Dispatchers.IO) { withTimeout(15000) { entered.await() } }
            runCurrent()
            assertFalse("Exit must not report success while its accepted password operation is still unable to commit", exit.isCompleted)
        } finally {
            if (initialHeld) mutex.unlock()
            release.complete(Unit)
            blocker?.join()
            // Finish real hashing/SQL even after a failed audit assertion; do not strand a fixture worker.
            vm.saveNow(force = true)
            exit?.await()
        }
        val saved = AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertEquals(due, saved.taskDueAt); assertTrue(saved.isProtected); assertFalse(saved.isDeleted)
        assertTrue(PasswordProtector.verify(password, PasswordRecord(saved.passwordHash!!, saved.passwordSalt!!)))
    }
}
