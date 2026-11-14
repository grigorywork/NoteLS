package com.notessync.app.reminders.audit

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Small valid fixtures exercise the Long ID contract; this is not a phone/billion-row benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlarmIdentityRegressionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); app.createReminderChannel(); notifications.cancelAll() }
    @After fun after() {
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { operation -> alarms.cancel(operation) } }
        notifications.cancelAll(); AppDatabase.closeInstance()
    }
    @Test fun `different Long note IDs with equal folded Int keep separate alarms`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000; val one = 1L; val two = 1L shl 32
        db.noteDao().upsert(NoteEntity(id = one, title = "One", reminderAt = at))
        db.noteDao().upsert(NoteEntity(id = two, title = "Two", reminderAt = at + 1000))
        assertTrue(ReminderScheduler.schedule(app, one, "One", at, db.noteDao().getById(one)!!.syncId)); assertTrue(ReminderScheduler.schedule(app, two, "Two", at + 1000, db.noteDao().getById(two)!!.syncId))
        assertEquals("Distinct persisted Long IDs must retain separate alarm identities", 2, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun `timer and reminder of different notes cannot collide through the timer salt`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000; val timer = 1L; val reminder = timer xor 0x51A7E3L
        db.noteDao().upsert(NoteEntity(id = timer, title = "Timer", isTask = true, taskCountdownMillis = 3600000, taskCountdownEndAt = at))
        db.noteDao().upsert(NoteEntity(id = reminder, title = "Reminder", reminderAt = at + 1000))
        assertTrue(ReminderScheduler.scheduleTimer(app, timer, "Timer", at, db.noteDao().getById(timer)!!.syncId)); assertTrue(ReminderScheduler.schedule(app, reminder, "Reminder", at + 1000, db.noteDao().getById(reminder)!!.syncId))
        assertEquals("Alarm kind and full ID must both participate in identity", 2, shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun `different Long note IDs retain separate delivered notifications`() = runBlocking {
        val at = System.currentTimeMillis() - 1
        for (id in listOf(1L, 1L shl 32)) {
            db.noteDao().upsert(NoteEntity(id = id, title = "Fixture $id", reminderAt = at))
            deliverReminder(app, ReminderScheduler.deliveryIntent(app, id, false, at, db.noteDao().getById(id)!!.syncId, "Fixture $id"))
        }
        assertEquals("Notification identity must not truncate the persisted Long ID", 2, notifications.activeNotifications.size)
    }
}
