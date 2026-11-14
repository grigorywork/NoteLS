package com.notessync.app.reminders.audit

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.reminders.*
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** Same behavioral contracts in separate JVM classes for minSdk, Android9 and Android15 API surfaces. */
abstract class AlarmDeliveryContract {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.createReminderChannel(); notifications.cancelAll(); assertTrue(ReminderScheduler.canNotify(app))
    }
    @After fun after() {
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { pending -> alarms.cancel(pending) } }
        notifications.cancelAll(); ProtectionSession.lockAll(); AppDatabase.closeInstance()
    }
    private suspend fun save(note: NoteEntity) = note.copy(id = db.noteDao().upsert(note))
    private fun intent(note: NoteEntity, timer: Boolean = false, oldTitle: String = "STALE TITLE") =
        ReminderScheduler.deliveryIntent(app, note.id, timer,
            (if (timer) note.taskCountdownEndAt else note.reminderAt)!!, note.syncId, oldTitle)
    @Test fun `current fresh title is used and an old deadline cannot deliver a newer target`() = runBlocking {
        val first = save(NoteEntity(title = "Old title", reminderAt = System.currentTimeMillis() - 2000))
        val queued = intent(first)
        val changed = first.copy(title = "Fresh title", reminderAt = first.reminderAt!! + 1000)
        db.noteDao().update(changed)
        deliverReminder(app, queued); assertEquals(0, notifications.activeNotifications.size)
        deliverReminder(app, intent(changed)); assertEquals(1, notifications.activeNotifications.size)
        assertEquals("Fresh title", notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
    @Test fun `reused numeric ID with a different global identity rejects an already queued signal`() = runBlocking {
        val first = save(NoteEntity(title = "First identity", reminderAt = System.currentTimeMillis() - 1))
        val queued = intent(first)
        val restored = first.copy(syncId = UUID.randomUUID().toString(), title = "Restored identity")
        db.noteDao().update(restored)
        deliverReminder(app, queued); assertEquals(0, notifications.activeNotifications.size)
        deliverReminder(app, intent(restored)); assertEquals(1, notifications.activeNotifications.size)
    }
    @Test fun `protected notebook title stays hidden even in an unlocked session`() = runBlocking {
        val bookId = db.notebookDao().upsert(NotebookEntity(name = "Protected book", isProtected = true))
        val note = save(NoteEntity(title = "SECRET PRIVATE TITLE", notebookId = bookId,
            content = "private body".repeat(10000), reminderAt = System.currentTimeMillis() - 1))
        ProtectionSession.unlockNotebook(bookId)
        val snapshot = db.noteDao().getAlarmSnapshot(note.id)!!
        assertTrue(snapshot.hidden); assertEquals("", snapshot.title)
        deliverReminder(app, intent(note, oldTitle = note.title))
        assertEquals("Защищённая запись", notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
    @Test fun `completed task rejects both queued kinds and active countdown is a positive control`() = runBlocking {
        val at = System.currentTimeMillis() - 1
        val note = save(NoteEntity(title = "Task", isTask = true, isCompleted = true, reminderAt = at,
            taskCountdownMillis = 1000, taskCountdownEndAt = at))
        deliverReminder(app, intent(note)); deliverReminder(app, intent(note, true))
        assertEquals(0, notifications.activeNotifications.size)
        db.noteDao().update(note.copy(isCompleted = false))
        deliverReminder(app, intent(note, true)); assertEquals(1, notifications.activeNotifications.size)
    }
    @Test fun `cancelling folded ID collision removes only that full alarm and notification identity`() = runBlocking {
        val due = System.currentTimeMillis() - 1; val one = 1L; val two = 1L shl 32
        val first = save(NoteEntity(id = one, title = "One", reminderAt = due))
        val second = save(NoteEntity(id = two, title = "Two", reminderAt = due))
        deliverReminder(app, intent(first)); deliverReminder(app, intent(second)); assertEquals(2, notifications.activeNotifications.size)
        val future = System.currentTimeMillis() + 3600000
        assertTrue(ReminderScheduler.schedule(app, one, "One", future, first.syncId))
        assertTrue(ReminderScheduler.schedule(app, two, "Two", future, second.syncId))
        ReminderScheduler.cancel(app, one)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertEquals(two, shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent.getLongExtra(ReminderReceiver.EXTRA_NOTE_ID, -1))
        assertEquals(1, notifications.activeNotifications.size)
        assertEquals(ReminderScheduler.notificationTag(two, false), notifications.activeNotifications.single().tag)
    }
    @Test fun `legacy signal without identity binding cannot notify`() = runBlocking {
        val note = save(NoteEntity(title = "Current fixture", reminderAt = System.currentTimeMillis() - 1))
        val receiver = ReminderReceiver()
        receiver.onReceive(app, Intent(app, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_NOTE_ID, note.id)
            .putExtra(ReminderReceiver.EXTRA_TITLE, "OLD PRIVATE TITLE"))
        assertFalse(shadowOf(receiver).wentAsync()); assertEquals(0, notifications.activeNotifications.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlarmDeliveryApi26Test : AlarmDeliveryContract()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlarmDeliveryApi28Test : AlarmDeliveryContract()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlarmDeliveryApi35Test : AlarmDeliveryContract()
