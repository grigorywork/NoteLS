package com.notessync.app.ui.notes.audit

import android.app.Application
import androidx.lifecycle.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.security.*
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorTagOwnershipAuditProbe {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val store = ViewModelStore()
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance()
        app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { store.clear(); AppDatabase.closeInstance(); ProtectionSession.lockAll(); Dispatchers.resetMain() }
    private suspend fun open(note: NoteEntity): NoteEditorViewModel {
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id)))
        store.put("editor", vm)
        withTimeout(15000) { while (!vm.isReady && !vm.accessDenied) delay(2) }
        assertTrue(vm.isReady); return vm
    }
    private suspend fun command(vm: NoteEditorViewModel, action: () -> Unit) {
        val parent = vm.viewModelScope.coroutineContext[Job]!!; val previous = parent.children.toSet()
        action(); withTimeout(15000) { parent.children.filter { it !in previous }.toList().joinAll() }
    }
    @Test fun `stale editor cannot attach a tag to replacement global identity`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Original", content = "Body"); db.noteDao().upsert(note)
        val vm = open(note)
        val replacement = note.copy(syncId = UUID.randomUUID().toString(), title = "Restored")
        db.noteDao().update(replacement)
        command(vm) { vm.addTag("stale-owner") }
        assertEquals(replacement, db.noteDao().getById(1))
        assertTrue("Failed fresh save must prevent tag mutation of replacement record", db.tagDao().getTagNamesForNote(1).isEmpty())
    }
    @Test fun `late editor cannot remove tags after fresh task protection`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Task", isTask = true); db.noteDao().upsert(note)
        val tag = db.tagDao().insert(TagEntity(name = "owner-tag")); db.tagDao().attachToNote(NoteTagCrossRef(1, tag))
        val vm = open(note)
        val record = PasswordProtector.create("PUBLIC FIXTURE PASSWORD")
        db.noteDao().update(note.copy(isProtected = true, passwordHash = record.hash, passwordSalt = record.salt))
        command(vm) { vm.removeTag("owner-tag") }
        assertEquals("Locked fresh parent owns its tag relations", listOf("owner-tag"), db.tagDao().getTagNamesForNote(1))
    }
    @Test fun `tag only change marks clean parent dirty for synchronization`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Clean", dirty = false); db.noteDao().upsert(note)
        val vm = open(note)
        command(vm) { vm.addTag("new-tag") }
        assertEquals(listOf("new-tag"), db.tagDao().getTagNamesForNote(1))
        val current = db.noteDao().getById(1)!!
        assertTrue("Relation-only edit must participate in parent conditional ACK", current.dirty)
        assertTrue("Relation-only edit needs a new parent version", current.updatedAt > note.updatedAt)
    }
}
