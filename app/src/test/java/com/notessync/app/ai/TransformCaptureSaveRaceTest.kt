package com.notessync.app.ai

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.dao.NoteDao
import com.notessync.app.data.local.model.TransformAccess
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
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransformCaptureSaveRaceTest {
    private val dispatcher=StandardTestDispatcher()
    private val models=ViewModelStore()
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var vm:NoteEditorViewModel
    @Before fun before() {
        Dispatchers.setMain(dispatcher);AppDatabase.closeInstance();app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL);setAiEnabled(true) }
        vm=NoteEditorViewModel(app,SavedStateHandle());models.put("editor",vm);dispatcher.scheduler.runCurrent()
        assertTrue(vm.isReady)
    }
    @After fun after() { models.clear();dispatcher.scheduler.runCurrent();SettingsRepository.get(app).setAiEnabled(false);AppDatabase.closeInstance();Dispatchers.resetMain() }
    private class ReadBarrier(private val dao:NoteDao):NoteDao by dao {
        val read=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        private val first=AtomicBoolean(true)
        override suspend fun getTransformAccess(id:Long):TransformAccess? {
            val snapshot=dao.getTransformAccess(id)
            if(first.compareAndSet(true,false)) { read.complete(Unit);resume.await() }
            return snapshot
        }
    }
    private fun gate(dao:NoteDao)=ReadBarrier(dao).also {
        // Control the real Room read-completion boundary; no production test hook or fake database.
        NoteEditorViewModel::class.java.getDeclaredField("noteDao").apply{isAccessible=true;set(vm,it)}
    }
    @Test fun `opening transform during own save captures committed current text instead of false stale revision`()=runBlocking {
        vm.onContentChange(TextFieldValue("Original"));assertTrue(vm.saveNow(force=true))
        val dao=AppDatabase.get(app).noteDao();val original=dao.getById(vm.currentNoteId)!!
        vm.onContentChange(TextFieldValue("Keep original"))
        val barrier=gate(dao)
        val capture=async(Dispatchers.Default){runCatching{vm.captureTransformSource()}}
        withTimeout(15_000){barrier.read.await()}
        val save=async(Dispatchers.Default){vm.saveNow(force=true)}
        try {
            // The old implementation commits its own new revision while comparing an old access read.
            // Correct serialization queues that save. Release either case; never deadlock a green test.
            val completedWhileReadHeld=withTimeoutOrNull(1_500){save.await()}
            println("Own save completed during held capture read: $completedWhileReadHeld")
        } finally { barrier.resume.complete(Unit) }
        val source=withTimeout(15_000){capture.await()}.getOrThrow()
        assertTrue(withTimeout(15_000){save.await()})
        val committed=dao.getById(vm.currentNoteId)!!
        assertEquals("Keep original",source.text);assertEquals("Keep original",committed.content)
        assertEquals(committed.updatedAt,source.access.updatedAt);assertTrue(committed.updatedAt>original.updatedAt)
        assertEquals(original.createdAt,committed.createdAt);assertEquals(original.syncId,committed.syncId)
    }
    @Test fun `protection revoked during capture read still refuses snapshot and preserves external row`()=runBlocking {
        vm.onContentChange(TextFieldValue("Original"));assertTrue(vm.saveNow(force=true))
        val dao=AppDatabase.get(app).noteDao();val original=dao.getById(vm.currentNoteId)!!
        vm.onContentChange(TextFieldValue("Unsaved local edit"))
        val barrier=gate(dao)
        val capture=async(Dispatchers.Default){runCatching{vm.captureTransformSource()}}
        withTimeout(15_000){barrier.read.await()}
        val locked=original.copy(isProtected=true,updatedAt=original.updatedAt+1)
        try { dao.update(locked) } finally { barrier.resume.complete(Unit) }
        val failure=withTimeout(15_000){capture.await()}.exceptionOrNull()
        assertTrue(failure is TransformException);assertEquals(TransformProblem.ACCESS,(failure as TransformException).problem)
        assertEquals(locked,dao.getById(vm.currentNoteId));assertEquals("Unsaved local edit",vm.contentField.text)
    }
    @Test fun `external content revision still rejects capture without overwriting peer text`()=runBlocking {
        vm.onContentChange(TextFieldValue("Original"));assertTrue(vm.saveNow(force=true))
        val dao=AppDatabase.get(app).noteDao();val original=dao.getById(vm.currentNoteId)!!
        val peer=original.copy(content="External peer edit",updatedAt=original.updatedAt+1)
        dao.update(peer);vm.onContentChange(TextFieldValue("Unsaved local edit"))
        val failure=runCatching{vm.captureTransformSource()}.exceptionOrNull()
        assertTrue(failure is TransformException);assertEquals(TransformProblem.STALE,(failure as TransformException).problem)
        assertEquals(peer,dao.getById(vm.currentNoteId));assertEquals("Unsaved local edit",vm.contentField.text)
    }
}
