package com.notessync.app.security.audit

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.ProtectionSession
import com.notessync.app.ui.notes.NotesViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** A stale UI row must not authorize a changed/purged task. Uses real password derivation and Native Room. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskStalePasswordRegressionTest {
    private val store = ViewModelStore()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private lateinit var vm: NotesViewModel
    private lateinit var captured: NoteEntity
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ProtectionSession.lockAll(); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        val old = PasswordProtector.create("old fixture password")
        val initial = NoteEntity(title = "Private fixture", content = "Private", isTask = true,
            isProtected = true, passwordHash = old.hash, passwordSalt = old.salt)
        captured = initial.copy(id = db.noteDao().upsert(initial))
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[NotesViewModel::class.java]
    }
    @After fun after() { store.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun unlock(): Boolean {
        val done = CompletableDeferred<Boolean>()
        vm.unlockTask(captured, "old fixture password") { done.complete(it) }
        return withTimeout(15000) { done.await() }
    }
    @Test fun `old UI password cannot authorize task whose credentials changed in the database`() = runBlocking {
        val changed = PasswordProtector.create("new fixture password")
        db.noteDao().update(captured.copy(passwordHash = changed.hash, passwordSalt = changed.salt,
            updatedAt = captured.updatedAt + 1))
        assertFalse("A changed credential must reject the old UI object", unlock())
        assertFalse(ProtectionSession.isUnlocked(captured))
    }
    @Test fun `stale card cannot grant access after task was purged`() = runBlocking {
        db.noteDao().update(captured.copy(isDeleted = true, isPurged = true))
        assertFalse("A purged task must not be authorized", unlock())
        assertFalse(ProtectionSession.isUnlocked(captured))
    }
}
