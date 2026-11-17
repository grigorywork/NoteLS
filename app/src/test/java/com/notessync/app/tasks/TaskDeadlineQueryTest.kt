package com.notessync.app.tasks

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.ui.notes.*
import com.notessync.app.ui.tasks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskDeadlineQueryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db") }
    @After fun after() { AppDatabase.closeInstance() }
    private suspend fun rows(filter: NoteFilter = NoteFilter.Unfinished, tasks: TaskFilters = TaskFilters(), search: String = "", books: Set<Long> = emptySet(), ids: Set<String> = emptySet()): List<NoteEntity> =
        AppDatabase.get(app).noteDao().rawQuery(NotesViewModel.buildQuery(search, filter, NoteSort.BY_DUE, books, ids, tasks, now, zone)).first()
    @Test fun `unfinished deadline filters separate exact due boundary completion deletion protection and unrelated reminder time`() = runBlocking {
        val db = AppDatabase.get(app); val dao = db.noteDao()
        val past = dao.upsert(NoteEntity(title = "Past", isTask = true, taskDueAt = now - 1))
        val exact = dao.upsert(NoteEntity(title = "Exact", isTask = true, taskDueAt = now))
        val future = dao.upsert(NoteEntity(title = "Future", isTask = true, taskDueAt = now + 86_400_000))
        val none = dao.upsert(NoteEntity(title = "Reminder only", isTask = true, reminderAt = now - 100, dayKey = 1, taskCountdownEndAt = now - 500))
        listOf(NoteEntity(isTask = true, isCompleted = true), NoteEntity(), NoteEntity(isTask = true, isDeleted = true), NoteEntity(isTask = true, isPurged = true)).forEach { dao.upsert(it.copy(taskDueAt = now - 10)) }
        val privateBook = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true))
        val secret = NoteEntity(isTask = true, isProtected = true, taskDueAt = now - 1)
        val secretId = dao.upsert(secret)
        val privateId = dao.upsert(NoteEntity(isTask = true, notebookId = privateBook, taskDueAt = now - 2))
        assertEquals(listOf(past, exact, future, none), rows().map { it.id })
        assertEquals(listOf(past), rows(NoteFilter.Overdue).map { it.id })
        assertEquals(listOf(exact, future), rows(tasks = TaskFilters(due = TaskDueFilter.UPCOMING)).map { it.id })
        assertEquals(listOf(past, exact), rows(tasks = TaskFilters(due = TaskDueFilter.TODAY)).map { it.id })
        assertEquals(listOf(none), rows(tasks = TaskFilters(due = TaskDueFilter.NONE)).map { it.id })
        assertEquals(setOf(past, privateId, secretId), rows(NoteFilter.Overdue, books = setOf(privateBook), ids = setOf(secret.syncId)).map { it.id }.toSet())
        val query = NotesViewModel.buildNextTaskDueQuery("", now = now, zone = zone)
        assertEquals(now, dao.observeNextTaskDue(query).first())
        val plan = db.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", arrayOf(now))).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
        assertTrue(plan.toString(), plan.any { it.contains("index_notes_task_due") })
    }
    @Test fun `priority notebook tag and escaped search combine and direct tag edits invalidate the actual query`() = runBlocking {
        val db = AppDatabase.get(app); val book = db.notebookDao().upsert(NotebookEntity(name = "Work"))
        val tag = db.tagDao().insert(TagEntity(name = "Tag"))
        val id = db.noteDao().upsert(NoteEntity(title = "100%_\\ literal", isTask = true, taskPriority = 3, notebookId = book, taskDueAt = now - 1))
        db.noteDao().upsert(NoteEntity(title = "100-other", isTask = true, taskPriority = 3, notebookId = book, taskDueAt = now - 1))
        val filters = TaskFilters(due = TaskDueFilter.OVERDUE, priority = 3, notebookId = book, tagId = tag)
        assertTrue(rows(tasks = filters, search = "100%_\\").isEmpty())
        val ready = CompletableDeferred<Unit>(); val seen = mutableListOf<List<Long>>()
        val query = NotesViewModel.buildQuery("100%_\\", NoteFilter.Unfinished, NoteSort.BY_DUE, tasks = filters, now = now, zone = zone)
        val collection = async { withTimeout(10_000) { db.noteDao().rawQuery(query).take(2).collect { seen.add(it.map { n -> n.id }); ready.complete(Unit) } } }
        ready.await(); db.tagDao().attachToNote(NoteTagCrossRef(id, tag)); collection.await()
        assertEquals(listOf(emptyList(), listOf(id)), seen)
        assertTrue(rows(tasks = filters.copy(priority = 2), search = "100%_\\").isEmpty())
        assertTrue(rows(tasks = filters.copy(notebookId = 0), search = "100%_\\").isEmpty())
        db.tagDao().detachFromNote(id, tag); assertTrue(rows(tasks = filters).isEmpty())
        assertEquals(3, db.noteDao().getById(id)!!.taskPriority)
    }
    @Test fun `running filter keeps a live stopwatch or countdown but not a paused or finished one`() = runBlocking {
        val dao = AppDatabase.get(app).noteDao()
        val watch = dao.upsert(NoteEntity(title = "Watch", isTask = true, taskElapsedMillis = 5_000, taskTimerStartedAt = now - 5_000))
        val countdown = dao.upsert(NoteEntity(title = "Countdown", isTask = true, taskCountdownMillis = 60_000, taskCountdownEndAt = now + 60_000))
        val paused = dao.upsert(NoteEntity(title = "Paused", isTask = true, taskCountdownMillis = 30_000))
        val finished = dao.upsert(NoteEntity(title = "Finished", isTask = true, taskCountdownEndAt = now))
        val plain = dao.upsert(NoteEntity(title = "Plain", isTask = true))
        dao.upsert(NoteEntity(title = "Completed", isTask = true, isCompleted = true, taskTimerStartedAt = now - 1_000, taskCountdownEndAt = now + 60_000))
        assertEquals(setOf(watch, countdown), rows(tasks = TaskFilters(running = true)).map { it.id }.toSet())
        assertEquals(setOf(watch, paused, countdown, finished, plain), rows().map { it.id }.toSet())
        // «Запущенные» работает вместе с остальными фильтрами, а не заменяет их.
        val overdue = dao.upsert(NoteEntity(title = "Overdue running", isTask = true, taskDueAt = now - 1, taskTimerStartedAt = now - 100))
        assertEquals(setOf(watch, countdown, overdue), rows(tasks = TaskFilters(running = true)).map { it.id }.toSet())
        assertEquals(setOf(overdue), rows(tasks = TaskFilters(running = true, due = TaskDueFilter.OVERDUE)).map { it.id }.toSet())
        // Планировщик ближайшего срока не должен зависеть от фильтра «Запущенные».
        val nextDue = AppDatabase.get(app).openHelper.readableDatabase
            .query(androidx.sqlite.db.SimpleSQLiteQuery(NotesViewModel.buildNextTaskDueQuery("", tasks = TaskFilters(running = true), now = now, zone = zone).sql, emptyArray()))
            .use { c -> c.moveToFirst(); if (c.isNull(0)) null else c.getLong(0) }
        assertEquals(now - 1, nextDue)
    }
    @Test fun `cached rows drop a countdown that already ended before SQL catches up`() {
        val running = com.notessync.app.data.local.model.NoteCardEntry(id = 1, isTask = true, taskTimerStartedAt = now - 1)
        val countdown = com.notessync.app.data.local.model.NoteCardEntry(id = 2, isTask = true, taskCountdownEndAt = now + 1)
        val ended = com.notessync.app.data.local.model.NoteCardEntry(id = 3, isTask = true, taskCountdownEndAt = now)
        val paused = com.notessync.app.data.local.model.NoteCardEntry(id = 4, isTask = true, taskCountdownMillis = 1_000)
        assertEquals(listOf(1L, 2L), filterCachedTasks(listOf(running, countdown, ended, paused), TaskDueFilter.ALL, true, now, zone).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), filterCachedTasks(listOf(running, countdown, ended, paused), TaskDueFilter.ALL, false, now, zone).map { it.id })
    }
    @Test fun `today uses local midnight bounds across a DST change without treating reminders as deadlines`() = runBlocking {
        val db = AppDatabase.get(app); val berlin = ZoneId.of("Europe/Berlin")
        val clock = Instant.parse("2024-03-31T12:00:00Z").toEpochMilli()
        val (start, end) = TaskDeadlines.dayBounds(clock, berlin)
        assertEquals(23 * 3_600_000L, end - start)
        val include = listOf(start, end - 1).map { db.noteDao().upsert(NoteEntity(isTask = true, taskDueAt = it)) }
        listOf(start - 1, end).forEach { db.noteDao().upsert(NoteEntity(isTask = true, taskDueAt = it)) }
        val q = NotesViewModel.buildQuery("", NoteFilter.Unfinished, NoteSort.BY_DUE, tasks = TaskFilters(due = TaskDueFilter.TODAY), now = clock, zone = berlin)
        assertEquals(include, db.noteDao().rawQuery(q).first().map { it.id })
        val autumn = TaskDeadlines.dayBounds(Instant.parse("2024-10-27T12:00:00Z").toEpochMilli(), berlin)
        assertEquals(25 * 3_600_000L, autumn.second - autumn.first)
    }
}
