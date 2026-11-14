package com.notessync.app.ui.notes.audit

import android.app.Application
import androidx.lifecycle.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.notes.photos.PhotoDisplaySize
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorMediaOwnershipAuditProbe {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val store = ViewModelStore()
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL); setAiEnabled(true) }; Unit
    }
    @After fun after() { store.clear(); SettingsRepository.get(app).setAiEnabled(false); AppDatabase.closeInstance(); ProtectionSession.lockAll(); Dispatchers.resetMain() }
    private suspend fun open(note: NoteEntity): NoteEditorViewModel {
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id)))
        store.put("editor", vm); withTimeout(15000) { while (!vm.isReady && !vm.accessDenied) delay(2) }; assertTrue(vm.isReady); return vm
    }
    private suspend fun photo(note: NoteEntity): AttachmentEntity {
        val value = AttachmentEntity(noteId = note.id, filePath = "/synthetic-unused.jpg", mimeType = "image/jpeg", sizeBytes = 1)
        return value.copy(id = db.attachmentDao().upsert(value))
    }
    private suspend fun command(vm: NoteEditorViewModel, action: () -> Unit) {
        val parent = vm.viewModelScope.coroutineContext[Job]!!; val previous = parent.children.toSet()
        action(); withTimeout(15000) { parent.children.filter { it !in previous }.toList().joinAll() }
    }
    @Test fun `photo resize cannot mutate a freshly locked parent`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Owner", content = "Body", isTask = true); db.noteDao().upsert(note)
        val picture = photo(note); val vm = open(note)
        val password = PasswordProtector.create("PUBLIC FIXTURE PASSWORD")
        db.noteDao().update(note.copy(isProtected = true, passwordHash = password.hash, passwordSalt = password.salt))
        assertFalse("Resize requires fresh parent authorization inside its write transaction", vm.savePhotoSize(picture, PhotoDisplaySize(300, 300)))
        assertEquals(picture, db.attachmentDao().getBySyncId(picture.syncId))
    }
    @Test fun `own photo metadata change does not disable a new local transform capture`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Owner", content = "Original fixture body"); db.noteDao().upsert(note)
        val picture = photo(note); val vm = open(note)
        assertTrue(vm.savePhotoSize(picture, PhotoDisplaySize(300, 300)))
        assertEquals(note.content, vm.captureTransformSource().text)
    }
    @Test fun `own tag change does not disable a new local transform capture`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Owner", content = "Original fixture body"); db.noteDao().upsert(note)
        val vm = open(note); command(vm) { vm.addTag("own-tag") }
        assertEquals(listOf("own-tag"), db.tagDao().getTagNamesForNote(1))
        assertEquals(note.content, vm.captureTransformSource().text)
    }
}
