package com.notessync.app.ui.notes.audit

import android.app.Application
import androidx.lifecycle.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.ai.*
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
class EditorConversionCacheRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val store = ViewModelStore()
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL); setAiEnabled(true) }; Unit
    }
    @After fun after() { store.clear(); SettingsRepository.get(app).setAiEnabled(false); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun open(note: NoteEntity): NoteEditorViewModel {
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id))); store.put("editor", vm)
        withTimeout(15000) { while (!vm.isReady) delay(2) }; return vm
    }
    @Test fun `conversion then save cannot overwrite newer peer document with unchanged editor cache`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Before", content = "Old editor body", isTask = true)
        db.noteDao().upsert(note); val vm = open(note)
        val peer = note.copy(title = "New peer title", content = "New peer body", updatedAt = note.updatedAt + 1)
        db.noteDao().update(peer)
        vm.toggleTask(); assertTrue(vm.saveNow(force = true))
        val saved = db.noteDao().getById(1)!!
        assertFalse(saved.isTask)
        assertEquals("Conversion must retain the latest committed body through later no-op saves", peer.content, saved.content)
        assertEquals(peer.title, saved.title)
    }
    @Test fun `own protection command cannot bless a stale peer document for transformation`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Before", content = "Old editor body", isTask = true)
        db.noteDao().upsert(note); val vm = open(note)
        val peer = note.copy(content = "New peer body", updatedAt = note.updatedAt + 1); db.noteDao().update(peer)
        vm.protectTask("PUBLIC OWNER PASSWORD"); assertTrue(vm.saveNow(force = true))
        val current = db.noteDao().getById(1)!!; assertTrue(current.isProtected); assertEquals(peer.content, current.content)
        val error = runCatching { vm.captureTransformSource() }.exceptionOrNull()
        assertTrue("New credentials must not make an unchanged stale document current", error is TransformException)
        assertEquals(TransformProblem.STALE, (error as TransformException).problem)
    }
    @Test fun `local text typed during an accepted conversion still saves`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Owner", content = "Before", isTask = true); db.noteDao().upsert(note)
        val vm = open(note); vm.toggleTask()
        vm.onContentChange(androidx.compose.ui.text.input.TextFieldValue("Typed during conversion"))
        assertTrue(vm.saveNow(force = true)); assertFalse(db.noteDao().getById(1)!!.isTask)
        assertEquals("Typed during conversion", db.noteDao().getById(1)!!.content)
    }
    @Test fun `own protection of current body allows a fresh local transform capture`() = runBlocking {
        val note = NoteEntity(id = 1, title = "Owner", content = "Current fixture body", isTask = true); db.noteDao().upsert(note)
        val vm = open(note); vm.protectTask("PUBLIC OWNER PASSWORD"); assertTrue(vm.saveNow(force = true))
        assertTrue(db.noteDao().getById(1)!!.isProtected)
        assertEquals(note.content, vm.captureTransformSource().text)
    }

}
