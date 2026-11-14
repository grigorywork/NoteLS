package com.notessync.app.sync.audit

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.*
import com.notessync.app.sync.android.RoomSyncDataSource
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room peer-data application with public synthetic DTOs; no network peer or phone timing claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncAlarmRegressionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); app.createReminderChannel(); app.getSharedPreferences("alarm_recovery", 0).edit().clear().commit() }
    @After fun after() {
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { pending -> alarms.cancel(pending) } }
        AppDatabase.closeInstance()
    }
    private suspend fun fixture(at: Long) = NoteEntity(title = "Peer fixture", isTask = true, dirty = false,
        reminderAt = at, taskCountdownMillis = 3600000, taskCountdownEndAt = at + 1000)
        .let { it.copy(id = db.noteDao().upsert(it)) }
    @Test fun `new peer task arms both received future reminder and countdown`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000
        val note = fixture(at); val source = RoomSyncDataSource(app, db)
        val remote = source.getChangesSince(Long.MIN_VALUE)
        db.openHelper.writableDatabase.execSQL("DELETE FROM notes") // Only the synthetic target fixture; emulate a new peer identity.
        val result = source.applyChanges(remote)
        assertEquals(1, result.appliedNotes)
        val stored = db.noteDao().getBySyncId(note.syncId)!!
        val deliveries = shadowOf(alarms).scheduledAlarms.map { shadowOf(it.operation).savedIntent }
        assertEquals("Both received future targets must be registered locally", 2, deliveries.size)
        assertTrue(deliveries.all { it.getLongExtra(ReminderReceiver.EXTRA_NOTE_ID, -1) == stored.id })
    }
    @Test fun `newer peer target replaces the old alarm time`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000
        val note = fixture(at); val source = RoomSyncDataSource(app, db)
        assertTrue(ReminderScheduler.schedule(app, note.id, note.title, at, note.syncId))
        val before = source.getChangesSince(Long.MIN_VALUE)
        val target = at + 7200000
        source.applyChanges(before.copy(notes = before.notes.map { it.copy(updatedAt = it.updatedAt + 1000,
            reminderAt = target, taskCountdownEndAt = null, taskCountdownMillis = 0) }))
        assertEquals(target, db.noteDao().getById(note.id)!!.reminderAt)
        assertEquals("Received later reminder must replace previous local alarm", target,
            shadowOf(alarms).scheduledAlarms.single().triggerAtMs)
    }
    @Test fun `peer clearing active targets removes obsolete alarms rather than leaving useless wakeups`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000
        val note = fixture(at); val source = RoomSyncDataSource(app, db)
        assertTrue(ReminderScheduler.schedule(app, note.id, note.title, at, note.syncId))
        assertTrue(ReminderScheduler.scheduleTimer(app, note.id, note.title, at + 1000, note.syncId))
        val before = source.getChangesSince(Long.MIN_VALUE)
        source.applyChanges(before.copy(notes = before.notes.map { it.copy(updatedAt = it.updatedAt + 1000,
            reminderAt = null, taskCountdownEndAt = null, taskCountdownMillis = 0) }))
        assertNull(db.noteDao().getById(note.id)!!.reminderAt)
        assertTrue("Cleared peer targets must leave no old alarm registration", shadowOf(alarms).scheduledAlarms.isEmpty())
    }
}
