package com.notessync.app.widget

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuickTaskStoreTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var store: QuickTaskStore
    @Before fun before() {
        app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        db = AppDatabase.get(app); store = QuickTaskStore(app)
    }
    @After fun after() { AppDatabase.closeInstance() }
    private fun draft(text: String, at: Long? = null) = QuickTaskDraft(UUID.randomUUID().toString(), text, 25_000L, at)
    private fun count(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes").use { it.moveToFirst(); it.getInt(0) }

    @Test fun `overlapping instances keep one identity and later updates preserve attachment foreign keys`() = runBlocking {
        val d = draft("First")
        val ids = listOf(async { store.save(d).id }, async { QuickTaskStore(app).save(d.copy(text = "Second")).id }).awaitAll()
        assertEquals(ids[0], ids[1]); assertEquals(1, count())
        db.attachmentDao().upsert(AttachmentEntity(noteId = ids[0], filePath = "test-original", mimeType = "image/jpeg", sizeBytes = 1))
        store.save(d.copy(text = "Updated"))
        val note = db.noteDao().getById(ids[0])!!
        assertEquals("Updated", note.content); assertTrue(note.isTask); assertEquals(25_000L, note.dayKey)
        assertEquals(1, db.attachmentDao().getByNoteId(ids[0]).size)
    }

    @Test fun `blank input never creates a row and clearing an existing draft leaves a tombstone`() = runBlocking {
        val d = draft(" \n ")
        assertEquals(0L, store.save(d).id); assertEquals(0, count())
        val id = store.save(d.copy(text = "Task")).id
        assertTrue(store.save(d).removed)
        assertTrue(db.noteDao().getById(id)!!.isDeleted)
        assertFalse(store.save(d).removed)
    }

    @Test fun `selected reminder reaches AlarmManager and removing it cancels the same alarm`() = runBlocking {
        val at = System.currentTimeMillis() + 3_600_000L
        val d = draft("Reminder", at)
        val result = store.save(d)
        assertNull(result.warning)
        assertEquals(at, db.noteDao().getById(result.id)!!.reminderAt)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == at })
        store.save(d.copy(reminderAt = null))
        assertNull(db.noteDao().getById(result.id)!!.reminderAt)
        assertTrue(alarms.scheduledAlarms.none { it.triggerAtTime == at })
    }

    @Test fun `past or blocked reminder retains task text and reports that no alarm was set`() = runBlocking {
        val past = store.save(draft("Keep past task", System.currentTimeMillis() - 1))
        assertEquals(QuickTaskWarning.PAST, past.warning)
        assertEquals("Keep past task", db.noteDao().getById(past.id)!!.content)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        notifications.setNotificationsEnabled(false)
        try {
            val denied = store.save(draft("Keep denied task", System.currentTimeMillis() + 60_000))
            assertEquals(QuickTaskWarning.NOTIFICATIONS, denied.warning)
            assertNull(db.noteDao().getById(denied.id)!!.reminderAt)
        } finally { notifications.setNotificationsEnabled(true) }
    }

    @Test fun `deleted draft cannot be resurrected by a delayed save`() = runBlocking {
        val d = draft("Task"); val id = store.save(d).id
        db.noteDao().softDelete(id)
        try { store.save(d.copy(text = "Late")); fail() } catch (_: IllegalStateException) { }
        assertTrue(db.noteDao().getById(id)!!.isDeleted)
    }

    @Test fun `schedule failure cancels the previous alarm and clears only reminder metadata`() = runBlocking {
        val d = draft("Task", System.currentTimeMillis() + 3_600_000L)
        val id = store.save(d).id
        val cancelled = mutableListOf<Long>()
        val failing = QuickTaskStore(app, object : QuickTaskReminders {
            override fun canNotify() = true
            override fun schedule(id: Long, title: String, at: Long): Boolean {
                db.openHelper.writableDatabase.execSQL("UPDATE notes SET taskPriority = 3 WHERE id = $id")
                return false
            }
            override fun cancel(id: Long) { cancelled += id }
        })
        val result = failing.save(d.copy(reminderAt = d.reminderAt!! + 60_000))
        assertEquals(QuickTaskWarning.SCHEDULE, result.warning)
        assertEquals(listOf(id), cancelled)
        val note = db.noteDao().getById(id)!!
        assertNull(note.reminderAt); assertEquals(3, note.taskPriority); assertEquals("Task", note.content)
    }

    @Test fun `editing a completed task preserves completion and does not rearm its reminder`() = runBlocking {
        val d = draft("Task", System.currentTimeMillis() + 3_600_000L)
        val id = store.save(d).id
        DayTaskActions.setCompleted(app, id, d.dayKey, true)
        store.save(d.copy(text = "Edited completed task"))
        assertTrue(db.noteDao().getById(id)!!.isCompleted)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.none { it.triggerAtTime == d.reminderAt })
    }
}
