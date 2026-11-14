package com.notessync.app.widget.audit

import android.app.AlarmManager
import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.ReminderReceiver
import com.notessync.app.widget.DayTaskActions
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayReopenAlarmRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db") }
    @After fun after() {
        Shadows.shadowOf(alarms).scheduledAlarms.toList().forEach { alarm -> alarm.operation?.let { alarms.cancel(it); it.cancel() } }
        AppDatabase.closeInstance()
    }
    @Test fun `day uncheck re-arms persisted future targets of a completed task`() = runBlocking {
        val now = System.currentTimeMillis(); val at = now + 3600000
        val note = NoteEntity(id = 1, title = "Completed peer task", dayKey = 20000, isTask = true,
            isCompleted = true, reminderAt = at, taskCountdownMillis = 7200000, taskCountdownEndAt = at + 3600000)
        db.noteDao().upsert(note)
        assertTrue(Shadows.shadowOf(alarms).scheduledAlarms.isEmpty())
        assertTrue(DayTaskActions.setCompleted(app, 1, 20000, false, expectedSyncId = note.syncId))
        val current = db.noteDao().getById(1)!!
        assertFalse(current.isCompleted); assertEquals(at, current.reminderAt); assertEquals(at + 3600000, current.taskCountdownEndAt)
        val deliveries = Shadows.shadowOf(alarms).scheduledAlarms
        assertEquals("Fresh active targets require both alarm kinds after reopening", 2, deliveries.size)
        assertEquals(setOf(false, true), deliveries.map { Shadows.shadowOf(it.operation).savedIntent.getBooleanExtra(ReminderReceiver.EXTRA_IS_TIMER, false) }.toSet())
    }
}
