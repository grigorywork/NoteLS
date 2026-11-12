package com.notessync.app.ui.analytics

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.ui.tasks.TaskCompletion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalyticsProjectionTest {
    private lateinit var db: AppDatabase
    @Before fun before() { AppDatabase.closeInstance(); val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("notessync.db"); db = AppDatabase.get(app) }
    @After fun after() { AppDatabase.closeInstance() }
    private suspend fun summary(scope: AnalyticsScope = AnalyticsScope.All): AnalyticsState {
        val zone = ZoneId.systemDefault()
        return AnalyticsSummary.build(db.noteDao().observeForAnalytics().first(), db.notebookDao().observeForAnalytics().first(), scope, LocalDate.now(zone), zone)
    }
    @Test fun `SQLite projection excludes all trash and protected records and bounds Unicode previews without changing data`() = runBlocking {
        val privateBook = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true, isDeleted = true))
        val original = "😀".repeat(600)
        val plain = db.noteDao().upsert(NoteEntity(title = original, content = "ordinary body".repeat(10_000)))
        val task = db.noteDao().upsert(NoteEntity(title = original, content = "task body".repeat(10_000), isTask = true, isCompleted = true))
        db.noteDao().upsert(NoteEntity(isDeleted = true)); db.noteDao().upsert(NoteEntity(isPurged = true))
        db.noteDao().upsert(NoteEntity(isProtected = true, isTask = true))
        db.noteDao().upsert(NoteEntity(notebookId = privateBook))
        val rows = db.noteDao().observeForAnalytics().first()
        assertEquals(setOf(plain, task), rows.map { it.id }.toSet())
        assertEquals("", rows.first { it.id == plain }.title)
        val preview = rows.first { it.id == task }.title
        assertEquals(512, preview.length); assertFalse(preview.last().isHighSurrogate()); assertFalse(preview.contains('�'))
        assertEquals(original, db.noteDao().getById(task)!!.title); assertEquals(90_000, db.noteDao().getById(task)!!.content.length)
    }
    @Test fun `calendar completion conversion trash restore and notebook deletion keep general counts coherent`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Work"))
        val plain = db.noteDao().upsert(NoteEntity(notebookId = book))
        db.noteDao().upsert(NoteEntity(isTask = true))
        assertEquals(1, summary().noteCount); assertEquals(1, summary().taskCount)
        db.noteDao().update(TaskCompletion.toggle(db.noteDao().getById(plain)!!.copy(isTask = true)))
        assertEquals(2, summary().totalEntries); assertEquals(0, summary().noteCount); assertEquals(2, summary().taskCount)
        assertEquals(1, summary().totalCompleted)
        db.noteDao().softDelete(plain); assertEquals(1, summary().totalEntries)
        db.noteDao().restore(plain); assertEquals(2, summary().totalEntries)
        db.notebookDao().softDelete(book)
        assertEquals(2, summary(AnalyticsScope.Unfiled).totalEntries)
        assertEquals(AnalyticsScope.All, summary(AnalyticsScope.Notebook(book)).scope)
    }
    @Test fun `notebook protection alone invalidates the observed entry query`() = runBlocking {
        val book = NotebookEntity(name = "Work").let { it.copy(id = db.notebookDao().upsert(it)) }
        db.noteDao().upsert(NoteEntity(title = "Should disappear", notebookId = book.id, isTask = true))
        val updates = Channel<Int>(Channel.UNLIMITED)
        val job = launch { db.noteDao().observeForAnalytics().collect { updates.send(it.size) } }
        try {
            assertEquals(1, withTimeout(10_000) { updates.receive() })
            db.notebookDao().update(book.copy(isProtected = true))
            withTimeout(10_000) { while (updates.receive() != 0) { /* Skip any older invalidation. */ } }
            assertEquals(0, summary().totalEntries)
        } finally { job.cancelAndJoin(); updates.close() }
    }
}
