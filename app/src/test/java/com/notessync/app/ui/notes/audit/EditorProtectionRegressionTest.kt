package com.notessync.app.ui.notes.audit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.security.*
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
import android.app.Application

/** Late protection commands must not grant access or report a write rejected by the fresh DB guard. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorProtectionRegressionTest {
    private val models = ViewModelStore()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher()); ProtectionSession.lockAll()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun open(id: Long, key: String): NoteEditorViewModel {
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id, "isTask" to true)))
        models.put(key, vm)
        withTimeout(15000) { while (!vm.isReady) delay(5) }
        return vm
    }
    @Test fun `late protect command cannot unlock a task when its save was denied`() = runBlocking {
        val original = NoteEntity(title = "Public fixture", content = "Public body", isTask = true)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(original.id, "original")
        val peer = PasswordProtector.create("peer fixture password")
        val changed = original.copy(content = "New private fixture", isProtected = true,
            passwordHash = peer.hash, passwordSalt = peer.salt, updatedAt = original.updatedAt + 1)
        db.noteDao().update(changed); ProtectionSession.lockTask(original.syncId)
        val parent = vm.viewModelScope.coroutineContext[Job]!!
        val existing = parent.children.toSet()
        vm.protectTask("late local fixture password")
        withTimeout(15000) { parent.children.filter { it !in existing }.toList().joinAll() }
        assertTrue("Fresh save guard must reject the stale editor", vm.accessDenied)
        assertEquals(peer.hash, db.noteDao().getById(original.id)!!.passwordHash)
        val granted = ProtectionSession.isUnlocked(changed)
        if (granted) {
            val reopened = open(original.id, "reopened")
            assertEquals("New private fixture", reopened.contentField.text) // Positive witness of the wrongly granted read.
        }
        assertFalse("A rejected protection save must never grant access", granted)
    }
    @Test fun `stale password removal cannot report success when newer protection was not changed`() = runBlocking {
        val old = PasswordProtector.create("old fixture password")
        val original = NoteEntity(title = "Protected fixture", isTask = true, isProtected = true,
            passwordHash = old.hash, passwordSalt = old.salt).let { it.copy(id = db.noteDao().upsert(it)) }
        ProtectionSession.unlockTask(original.syncId)
        val vm = open(original.id, "original")
        val peer = PasswordProtector.create("new fixture password")
        db.noteDao().update(original.copy(passwordHash = peer.hash, passwordSalt = peer.salt, updatedAt = original.updatedAt + 1))
        ProtectionSession.lockTask(original.syncId)
        val done = CompletableDeferred<Boolean>()
        vm.removeTaskProtection("old fixture password") { done.complete(it) }
        val accepted = withTimeout(15000) { done.await() }
        assertTrue(db.noteDao().getById(original.id)!!.isProtected)
        assertEquals(peer.hash, db.noteDao().getById(original.id)!!.passwordHash)
        assertFalse("A rejected removal must not be reported as successful", accepted)
    }
}
