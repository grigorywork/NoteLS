package com.notessync.app.tasks.running

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.dao.RUNNING_TASKS_SQL
import com.notessync.app.data.local.entity.*
import com.notessync.app.security.ProtectionSession
import com.notessync.app.widget.WidgetNoteLinks
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RunningTaskQueryActionsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val now = 1_900_000_000_000L
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll() }
    @After fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    private suspend fun save(note: NoteEntity) = note.copy(id = db.noteDao().upsert(note))
    private fun control(note: NoteEntity, clock: RunningClock = RunningClock.STOPWATCH, action: RunningAction = RunningAction.PAUSE) =
        RunningControl(note.id, note.syncId, clock, requireNotNull(if (clock == RunningClock.STOPWATCH) note.taskTimerStartedAt else note.taskCountdownEndAt), action)
    @Test fun `bounded query hides even unlocked private titles and excludes expired completed trash and deleted parents`() = runBlocking {
        val privateBook = db.notebookDao().upsert(NotebookEntity(name = "Secret notebook", isProtected = true))
        val diary = db.notebookDao().upsert(NotebookEntity(name = "Diary", isDiary = true))
        val deleted = db.notebookDao().upsert(NotebookEntity(name = "Deleted", isDeleted = true))
        val public = save(NoteEntity(title = "😀".repeat(500), content = "body".repeat(100_000), isTask = true, taskTimerStartedAt = now - 1000))
        val hidden = save(NoteEntity(title = "Never load this private title", isTask = true, isProtected = true, taskTimerStartedAt = now))
        ProtectionSession.unlockTask(hidden.syncId); ProtectionSession.unlockNotebook(privateBook)
        save(public.copy(id = 0, syncId = "private-parent", notebookId = privateBook))
        save(public.copy(id = 0, syncId = "diary-parent", notebookId = diary))
        for (n in listOf(public.copy(id = 0, syncId = "completed", isCompleted = true), public.copy(id = 0, syncId = "trash", isDeleted = true),
            public.copy(id = 0, syncId = "purged", isPurged = true), public.copy(id = 0, syncId = "ordinary", isTask = false),
            public.copy(id = 0, syncId = "deleted-parent", notebookId = deleted), public.copy(id = 0, syncId = "expired", taskTimerStartedAt = null, taskCountdownEndAt = now),
            public.copy(id = 0, syncId = "x".repeat(161)))) save(n)
        val rows = db.noteDao().getRunningTasks(now)
        assertEquals(4, rows.size); assertEquals(512, rows.first().title.length)
        assertEquals(3, rows.count { it.hidden && it.title.isEmpty() })
        assertEquals(400_000, db.noteDao().getById(public.id)!!.content.length)
        repeat(30) { save(NoteEntity(title = "Bounded $it", isTask = true, taskTimerStartedAt = now)) }
        assertEquals(20, db.noteDao().getRunningTasks(now).size)
        val query = RUNNING_TASKS_SQL.replace(":now", now.toString())
        val plan = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $query").use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        assertTrue(plan.toString(), plan.any { it.contains("index_notes_task_due") })
        assertFalse(RUNNING_TASKS_SQL.contains("content")); assertFalse(RUNNING_TASKS_SQL.contains("password"))
    }
    @Test fun `pause changes only the selected clock and preserves latest document notebook table and metadata`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Work"))
        val note = save(NoteEntity(title = "Task", content = "old", notebookId = book, isTask = true,
            taskElapsedMillis = 7000, taskTimerStartedAt = now - 3000, taskCountdownMillis = 90_000, taskCountdownEndAt = now + 90_000,
            dayKey = 20_000, taskDueAt = now + 100, reminderAt = now + 200, createdAt = 77))
        db.noteDao().update(note.copy(content = "New text 😀", tablesData = "unknown-future-table", textFormat = "format", updatedAt = now))
        assertTrue(RunningTaskActions.apply(app, control(note), now))
        val fresh = db.noteDao().getById(note.id)!!
        assertEquals(10_000L, fresh.taskElapsedMillis); assertNull(fresh.taskTimerStartedAt)
        assertEquals(note.taskCountdownEndAt, fresh.taskCountdownEndAt); assertEquals(note.notebookId, fresh.notebookId)
        assertEquals("New text 😀", fresh.content); assertEquals("unknown-future-table", fresh.tablesData); assertEquals("format", fresh.textFormat)
        assertEquals(note.createdAt, fresh.createdAt); assertEquals(note.dayKey, fresh.dayKey); assertEquals(note.taskDueAt, fresh.taskDueAt)
        assertEquals(note.reminderAt, fresh.reminderAt); assertEquals(now + 1, fresh.updatedAt); assertTrue(fresh.dirty)
        assertFalse(RunningTaskActions.apply(app, control(note), now))
    }
    @Test fun `completion stops both clocks in place and unchecking returns the same notebook record`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Work"))
        val note = save(NoteEntity(title = "Both", notebookId = book, isTask = true, taskTimerStartedAt = now - 500,
            taskCountdownMillis = 50_000, taskCountdownEndAt = now + 30_000, reminderAt = now + 99))
        assertTrue(RunningTaskActions.apply(app, control(note, action = RunningAction.COMPLETE), now))
        val done = db.noteDao().getById(note.id)!!
        assertTrue(done.isCompleted); assertEquals(now, done.taskCompletedAt); assertEquals(500L, done.taskElapsedMillis)
        assertNull(done.taskTimerStartedAt); assertNull(done.taskCountdownEndAt); assertNull(done.reminderAt)
        assertEquals(30_000L, done.taskCountdownMillis); assertEquals(book, done.notebookId); assertEquals(note.syncId, done.syncId)
        db.noteDao().update(com.notessync.app.ui.tasks.TaskCompletion.toggle(done, now + 1))
        val returned = db.noteDao().getById(note.id)!!; assertFalse(returned.isCompleted); assertEquals(book, returned.notebookId)
        val corrupt = returned.copy(taskTimerStartedAt = now, taskCountdownMillis = -20)
        db.noteDao().update(corrupt)
        assertTrue(RunningTaskActions.apply(app, control(corrupt, action = RunningAction.COMPLETE), now + 1))
        assertEquals(0L, db.noteDao().getById(note.id)!!.taskCountdownMillis)
    }
    @Test fun `restarted clock reused numeric id and late expiry refuse stale actions without writes`() = runBlocking {
        val note = save(NoteEntity(title = "Task", isTask = true, taskTimerStartedAt = now - 10, taskCountdownEndAt = now + 100))
        val watch = control(note); val countdown = control(note, RunningClock.COUNTDOWN)
        val changed = note.copy(taskTimerStartedAt = now + 1, updatedAt = now)
        db.noteDao().update(changed); assertFalse(RunningTaskActions.apply(app, watch, now + 2)); assertEquals(changed, db.noteDao().getById(note.id))
        assertFalse(RunningTaskActions.apply(app, countdown, now + 100)); assertEquals(changed, db.noteDao().getById(note.id))
        val reused = changed.copy(syncId = "replacement"); db.noteDao().update(reused)
        assertFalse(RunningTaskActions.apply(app, countdown, now)); assertEquals(reused, db.noteDao().getById(note.id))
    }
    @Test fun `new protection diary or parent deletion revokes queued actions even when sessions are unlocked`() = runBlocking {
        val note = save(NoteEntity(title = "Public before", isTask = true, taskTimerStartedAt = now))
        val command = control(note)
        val protected = note.copy(isProtected = true); db.noteDao().update(protected); ProtectionSession.unlockTask(note.syncId)
        assertFalse(RunningTaskActions.apply(app, command, now + 1)); assertEquals(protected, db.noteDao().getById(note.id))
        val book = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true)); ProtectionSession.unlockNotebook(book)
        val moved = note.copy(notebookId = book); db.noteDao().update(moved)
        assertFalse(RunningTaskActions.apply(app, command, now + 1))
        db.notebookDao().update(db.notebookDao().getById(book)!!.copy(isProtected = false, isDiary = true))
        assertFalse(RunningTaskActions.apply(app, command, now + 1))
        db.notebookDao().update(db.notebookDao().getById(book)!!.copy(isProtected = false, isDiary = false, isDeleted = true))
        assertFalse(RunningTaskActions.apply(app, command, now + 1))
        val request = WidgetNoteLinks.read(WidgetNoteLinks.intent(app, -90, note.id, note.syncId), 1)!!
        assertFalse(WidgetNoteLinks.canOpen(app, request)); assertEquals(moved, db.noteDao().getById(note.id))
    }
    @Test fun `countdown pause leaves the running stopwatch and a failing SQL command rolls back the task`() = runBlocking {
        val note = save(NoteEntity(title = "Task", isTask = true, taskTimerStartedAt = now - 10, taskCountdownEndAt = now + 100))
        assertTrue(RunningTaskActions.apply(app, control(note, RunningClock.COUNTDOWN), now))
        val paused = db.noteDao().getById(note.id)!!; assertEquals(100L, paused.taskCountdownMillis)
        assertNull(paused.taskCountdownEndAt); assertEquals(note.taskTimerStartedAt, paused.taskTimerStartedAt)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER control_fail BEFORE UPDATE ON notes BEGIN SELECT RAISE(FAIL, 'disk fixture'); END")
        try { RunningTaskActions.apply(app, control(note), now + 1); fail("Expected write failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(paused, db.noteDao().getById(note.id))
    }
}
