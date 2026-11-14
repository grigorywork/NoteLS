package com.notessync.app.reminders.audit

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.reminders.*
import com.notessync.app.security.PasswordProtector
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Models an already queued OS broadcast. Tests real Receiver/Room/NotificationManager shadows, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderFreshStateRegressionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val privateTitle = "Sensitive fixture title"
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        app.createReminderChannel(); notifications.cancelAll()
        assertTrue(ReminderScheduler.canNotify(app))
    }
    @After fun after() { notifications.cancelAll(); AppDatabase.closeInstance() }
    private suspend fun delivery(id: Long, timer: Boolean = false): Intent {
        val note = db.noteDao().getById(id)!!
        val at = if (timer) note.taskCountdownEndAt!! else note.reminderAt!!
        return ReminderScheduler.deliveryIntent(app, id, timer, at, note.syncId, privateTitle)
    }
    @Test fun `queued old reminder cannot notify after reminder was cleared`() = runBlocking {
        val original = NoteEntity(title = privateTitle, reminderAt = System.currentTimeMillis() - 1000)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val queued = delivery(original.id)
        db.noteDao().update(original.copy(reminderAt = null))
        deliverReminder(app, queued)
        assertEquals("A cleared reminder must not notify", 0, notifications.activeNotifications.size)
    }
    @Test fun `queued timer cannot notify after task was deleted`() = runBlocking {
        val original = NoteEntity(title = privateTitle, isTask = true, taskCountdownMillis = 1000,
            taskCountdownEndAt = System.currentTimeMillis() - 1).let { it.copy(id = db.noteDao().upsert(it)) }
        val queued = delivery(original.id, true)
        db.noteDao().update(original.copy(isDeleted = true))
        deliverReminder(app, queued)
        assertEquals("A deleted task must not notify", 0, notifications.activeNotifications.size)
    }
    @Test fun `notebook protection enabled after scheduling hides old plaintext notification title`() = runBlocking {
        val book = NotebookEntity(name = "Private notebook").let { it.copy(id = db.notebookDao().upsert(it)) }
        val note = NoteEntity(title = privateTitle, notebookId = book.id, reminderAt = System.currentTimeMillis() - 1)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val queued = delivery(note.id)
        val record = PasswordProtector.create("fixture notebook password")
        db.notebookDao().update(book.copy(isProtected = true, passwordHash = record.hash, passwordSalt = record.salt))
        deliverReminder(app, queued)
        val visible = notifications.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse("A newly protected title must not leak through a queued intent", visible.contains(privateTitle))
        assertEquals("Защищённая запись", visible)
    }
}
