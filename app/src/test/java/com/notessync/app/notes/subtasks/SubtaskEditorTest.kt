package com.notessync.app.notes.subtasks

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.subtasks.*
import com.notessync.app.tasks.running.*
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubtaskEditorTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val db get()=AppDatabase.get(app)
    private val store=ViewModelStore()
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher());AppDatabase.closeInstance();app.deleteDatabase("notessync.db");SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL) }
    @After fun after() { store.clear();AppDatabase.closeInstance();Dispatchers.resetMain() }
    private suspend fun open(id:Long=-1L):NoteEditorViewModel {
        val vm=NoteEditorViewModel(app,SavedStateHandle(mapOf("noteId" to id)));store.put("editor",vm)
        withTimeout(15_000) { while(!vm.isReady) delay(5) };return vm
    }
    @Test fun `a new otherwise empty note with a plus-created child is saved and never discarded on exit`()=runBlocking {
        val vm=open();val child=vm.addSubtask()!!;vm.editSubtask(child,"Only child 😀")
        assertTrue(vm.saveNow());val parentId=vm.currentNoteId;assertTrue(parentId>0)
        assertTrue(vm.flushOnExit());assertFalse(db.noteDao().getById(parentId)!!.isDeleted)
        assertEquals("Only child 😀",db.subtaskDao().get(parentId).single().text)
        val reopened=open(parentId);assertEquals(child,reopened.subtasks.single().id);assertEquals("Only child 😀",reopened.subtasks.single().text)
    }
    @Test fun `an external child pause is reflected live without losing typed row text or restarting on save`()=runBlocking {
        val now=System.currentTimeMillis();val note=NoteEntity(content="Parent body").let { it.copy(id=db.noteDao().upsert(it)) }
        val row=Subtask("a","Old",startedAt=now-500)
        db.withTransaction { replaceSubtasks(db,note.id,listOf(row)) };val vm=open(note.id)
        vm.editSubtask("a","Unsaved child draft")
        assertTrue(RunningTaskActions.apply(app,RunningControl(note.id,note.syncId,RunningClock.STOPWATCH,row.startedAt!!,RunningAction.PAUSE,"a"),now))
        withTimeout(15_000) { while(vm.subtasks.single().startedAt!=null) delay(5) }
        assertEquals("Unsaved child draft",vm.subtasks.single().text);assertEquals(500L,vm.subtasks.single().elapsedMillis)
        assertTrue(vm.saveNow(true));val saved=db.subtaskDao().get(note.id).single()
        assertEquals("Unsaved child draft",saved.text);assertNull(saved.startedAt);assertEquals("Parent body",db.noteDao().getById(note.id)!!.content)
        vm.undo();assertNull(vm.subtasks.single().startedAt)
    }
    @Test fun `explicit local child restart survives metadata observer and no-op saves retain parent timestamp`()=runBlocking {
        val note=NoteEntity(content="Main").let { it.copy(id=db.noteDao().upsert(it)) }
        db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Keep"))) };val vm=open(note.id)
        assertTrue(vm.saveNow(true));assertEquals(note.updatedAt,db.noteDao().getById(note.id)!!.updatedAt)
        vm.toggleSubtaskStopwatch("a");val start=vm.subtasks.single().startedAt;assertNotNull(start)
        vm.onContentChange(TextFieldValue("Latest main"));assertTrue(vm.saveNow(true));assertEquals(start,db.subtaskDao().get(note.id).single().startedAt)
        assertTrue(vm.saveNow(true));assertEquals(start,vm.subtasks.single().startedAt)
    }
    @Test fun `external removal cannot be resurrected by stale local text and parent completion keeps child clocks paused`()=runBlocking {
        val note=NoteEntity(isTask=true).let { it.copy(id=db.noteDao().upsert(it)) }
        db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Old",startedAt=System.currentTimeMillis()))) }
        val vm=open(note.id);vm.editSubtask("a","Late text");db.subtaskDao().clear(note.id)
        assertTrue(vm.saveNow(true));assertTrue(db.subtaskDao().get(note.id).isEmpty());assertTrue(vm.subtasks.isEmpty())
        val id=vm.addSubtask()!!;vm.toggleSubtaskStopwatch(id);vm.toggleCompleted();assertTrue(vm.saveNow(true))
        assertNull(db.subtaskDao().get(note.id).single().startedAt);assertTrue(db.noteDao().getById(note.id)!!.isCompleted)
        vm.toggleSubtaskStopwatch(id);assertNull(vm.subtasks.single().startedAt)
    }
}
