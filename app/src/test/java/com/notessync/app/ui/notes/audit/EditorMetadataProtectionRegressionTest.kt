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
class EditorMetadataProtectionRegressionTest {
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
    @Test fun `authorized protected task conversion must save before its session grant is revoked`() = runBlocking {
        val password = PasswordProtector.create("owner fixture password")
        val note = NoteEntity(title = "Protected task", content = "Preserved text", isTask = true, isProtected = true,
            passwordHash = password.hash, passwordSalt = password.salt).let { it.copy(id = db.noteDao().upsert(it)) }
        ProtectionSession.unlockTask(note.syncId)
        val vm = open(note.id, "editor")
        vm.toggleTask()
        val accepted = vm.saveNow(force = true)
        assertTrue("Authorized conversion must not deny its own save", accepted)
        assertFalse(db.noteDao().getById(note.id)!!.isTask)
        assertEquals("Preserved text", db.noteDao().getById(note.id)!!.content)
    }
    @Test fun `ordinary text autosave cannot remove freshly enabled task protection from a stale editor`() = runBlocking {
        val note = NoteEntity(title = "Original public task", content = "Original body", isTask = true)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val vm = open(note.id, "editor")
        val password = PasswordProtector.create("fresh fixture password")
        db.noteDao().update(note.copy(isProtected = true, passwordHash = password.hash, passwordSalt = password.salt,
            updatedAt = note.updatedAt + 1))
        val home = com.notessync.app.ui.notes.NotesViewModel(app); models.put("home", home)
        val done = CompletableDeferred<Boolean>()
        home.unlockTask(note, "fresh fixture password") { done.complete(it) }
        assertTrue(withTimeout(15000) { done.await() }) // Fresh public controller actually authenticates the current owner.
        vm.onContentChange(androidx.compose.ui.text.input.TextFieldValue("Authorized text edit"))
        assertTrue(vm.saveNow(force = true))
        val current = db.noteDao().getById(note.id)!!
        assertEquals("Authorized text edit", current.content)
        assertTrue("A text save must preserve independently changed protection", current.isProtected)
        assertEquals(password.hash, current.passwordHash)
    }
}
