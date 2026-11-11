package com.notessync.app.widget

import android.app.Application
import android.content.Context
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayTaskActionsTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val day = 20_000L

    @Before fun setUp() {
        AppDatabase.closeInstance()
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("notessync.db")
        db = AppDatabase.get(context)
    }

    @After fun tearDown() { AppDatabase.closeInstance() }

    @Test fun `checking a quick note makes a completed task and unchecking restores active state`() = runBlocking {
        val id = db.noteDao().upsert(NoteEntity(title = "Из виджета", dayKey = day, dirty = false))
        assertFalse(db.noteDao().getById(id)!!.isTask)
        assertTrue(DayTaskActions.setCompleted(context, id, day, true, now = 100_000))
        val done = db.noteDao().observeByDay(day).first().single()
        assertTrue(done.isTask)
        assertTrue(done.isCompleted)
        assertEquals(100_000L, done.taskCompletedAt)
        assertEquals(100_000L, done.updatedAt)
        assertTrue(done.dirty)
        assertEquals("Из виджета", done.title)
        assertTrue(DayTaskActions.setCompleted(context, id, day, false, now = 200_000))
        val active = db.noteDao().getById(id)!!
        assertTrue(active.isTask)
        assertFalse(active.isCompleted)
        assertNull(active.taskCompletedAt)
        assertNull(active.taskTimerStartedAt)
        assertNull(active.taskCountdownEndAt)
    }

    @Test fun `duplicate concurrent checks commit once and do not overwrite a newer title`() = runBlocking {
        val id = db.noteDao().upsert(NoteEntity(title = "Старый", dayKey = day, isTask = true))
        db.noteDao().update(db.noteDao().getById(id)!!.copy(title = "Свежий текст", content = "Правка"))
        val changed = coroutineScope {
            (1..2).map { async { DayTaskActions.setCompleted(context, id, day, true, now = 123_000) } }.awaitAll()
        }
        assertEquals(1, changed.count { it })
        assertFalse(DayTaskActions.setCompleted(context, id, day, true, now = 999_000))
        val saved = db.noteDao().getById(id)!!
        assertEquals("Свежий текст", saved.title)
        assertEquals("Правка", saved.content)
        assertEquals(123_000L, saved.taskCompletedAt)
    }

    @Test fun `completing running task freezes both timers and clears reminder`() = runBlocking {
        val id = db.noteDao().upsert(NoteEntity(dayKey = day, isTask = true,
            taskElapsedMillis = 5_000, taskTimerStartedAt = 90_000,
            taskCountdownMillis = 600_000, taskCountdownEndAt = 700_000, reminderAt = 650_000))
        assertTrue(DayTaskActions.setCompleted(context, id, day, true, now = 100_000))
        val saved = db.noteDao().getById(id)!!
        assertEquals(15_000L, saved.taskElapsedMillis)
        assertEquals(600_000L, saved.taskCountdownMillis)
        assertNull(saved.taskTimerStartedAt)
        assertNull(saved.taskCountdownEndAt)
        assertNull(saved.reminderAt)
    }

    @Test fun `stale actions cannot edit deleted protected other-day or missing entries`() = runBlocking {
        val notebook = db.notebookDao().upsert(NotebookEntity(name = "Закрытый", isProtected = true))
        val cases = listOf(
            NoteEntity(dayKey = day + 1),
            NoteEntity(dayKey = day, isDeleted = true),
            NoteEntity(dayKey = day, isProtected = true),
            NoteEntity(dayKey = day, notebookId = notebook)
        )
        for (note in cases) {
            val id = db.noteDao().upsert(note)
            val before = db.noteDao().getById(id)
            assertFalse(DayTaskActions.setCompleted(context, id, day, true))
            assertEquals(before, db.noteDao().getById(id))
        }
        assertFalse(DayTaskActions.setCompleted(context, -1, day, true))
        val ordinary = db.noteDao().upsert(NoteEntity(dayKey = day))
        assertFalse(DayTaskActions.setCompleted(context, ordinary, day, false))
        assertFalse(db.noteDao().getById(ordinary)!!.isTask)
    }

    @Test fun `write failure rolls back without a checked database state`() = runBlocking {
        val id = db.noteDao().upsert(NoteEntity(title = "Сохранено", dayKey = day, dirty = false))
        val before = db.noteDao().getById(id)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_completion BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT, 'test'); END"
        )
        try {
            DayTaskActions.setCompleted(context, id, day, true)
            fail("Expected injected write failure")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertEquals(before, db.noteDao().getById(id))
        }
    }
}
