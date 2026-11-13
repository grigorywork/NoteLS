package com.notessync.app.widget

import android.app.Application
import androidx.sqlite.db.SimpleSQLiteQuery
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.dao.WIDGET_DAY_SNIPPETS_SQL
import com.notessync.app.data.local.entity.*
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.flow.first
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
class WeeklyWidgetQueryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val day = 20_000L
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll() }
    @After fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    @Test fun `calendar snapshots count every public entry but fetch only three short titles per day without bodies`() = runBlocking {
        val db = AppDatabase.get(app); val dao = db.noteDao()
        val book = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true)); ProtectionSession.unlockNotebook(book)
        val original = NoteEntity(title = "😀".repeat(600), content = "body".repeat(100_000), dayKey = day, isTask = true, createdAt = 1)
        val id = dao.upsert(original)
        val done = dao.upsert(NoteEntity(title = "Done", dayKey = day, isTask = true, isCompleted = true, createdAt = 0))
        repeat(48) { dao.upsert(NoteEntity(title = "Note $it", dayKey = day, createdAt = it.toLong())) }
        val next = dao.upsert(NoteEntity(title = "Next day", dayKey = day + 6))
        listOf(NoteEntity(isProtected = true), NoteEntity(notebookId = book), NoteEntity(isDeleted = true), NoteEntity(isPurged = true)).forEach {
            dao.upsert(it.copy(title = "Hidden", dayKey = day, isTask = true, createdAt = -100))
        }
        dao.upsert(NoteEntity(title = "Outside", dayKey = day + 7))
        val week = loadWidgetDays(db, day, day + 6)
        assertEquals(setOf(day, day + 6), week.keys); assertEquals(50L, week.getValue(day).total)
        val entries = week.getValue(day).entries
        assertEquals(3, entries.size); assertEquals(listOf(id, done), entries.take(2).map { it.id })
        assertEquals(512, entries.first().title.length); assertFalse(entries.first().title.last().isHighSurrogate())
        assertEquals(original.copy(id = id), dao.getById(id)); assertEquals(next, week.getValue(day + 6).entries.single().id)
        assertEquals("+47", WidgetDayPreview.lines(week.getValue(day).previews(), 50).last().text)
        assertFalse(dao.observeByDay(day).first().any { it.isPurged })
        val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $WIDGET_DAY_SNIPPETS_SQL", arrayOf(day))).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(3)) }
        }
        assertTrue(plan.toString(), plan.any { it.contains("index_notes_dayKey") })
        assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
    }
    @Test fun `bounded month snapshots retain aggregate overflow and reject an unbounded range`() = runBlocking {
        val db = AppDatabase.get(app)
        repeat(42) { offset -> repeat(4) { n -> db.noteDao().upsert(NoteEntity(title = "$offset-$n", dayKey = day + offset)) } }
        val month = loadWidgetDays(db, day, day + 41)
        assertEquals(42, month.size); assertEquals(126, month.values.sumOf { it.entries.size }); assertTrue(month.values.all { it.total == 4L })
        assertTrue(runCatching { loadWidgetDays(db, day, day + 42) }.isFailure)
        assertTrue(runCatching { loadWidgetDays(db, Long.MIN_VALUE, Long.MAX_VALUE) }.isFailure)
    }
    @Test fun `stale blank drafts cannot delete newly protected entries and stale checkbox identities cannot edit replacements`() = runBlocking {
        val db = AppDatabase.get(app); val dao = db.noteDao(); val store = QuickTaskStore(app)
        val draft = QuickTaskDraft("draft", "Original", day, null); val id = store.save(draft).id
        val protected = dao.getById(id)!!.copy(isProtected = true); dao.update(protected)
        assertTrue(runCatching { store.save(draft.copy(text = "")) }.isFailure); assertEquals(protected, dao.getById(id))
        val replacement = protected.copy(syncId = "replacement", isProtected = false); dao.update(replacement)
        assertFalse(DayTaskActions.setCompleted(app, id, day, true, expectedSyncId = draft.syncId)); assertEquals(replacement, dao.getById(id))
        val purged = replacement.copy(isPurged = true); dao.update(purged)
        assertFalse(DayTaskActions.setCompleted(app, id, day, true, expectedSyncId = purged.syncId)); assertEquals(purged, dao.getById(id))
    }
}
