package com.notessync.app.tasks.running

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.widget.WidgetNoteLinks
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RunningTaskNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val settings get() = SettingsRepository.get(app)
    private val db get() = AppDatabase.get(app)
    @Before fun before() {
        RunningTaskNotifications.stop(); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); manager.cancelAll()
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        settings.setUiLanguage(UiLanguage.RUSSIAN); settings.setRunningTaskNotifications(true)
    }
    @After fun after() { RunningTaskNotifications.stop(); AppDatabase.closeInstance(); manager.cancelAll(); settings.setRunningTaskNotifications(true); settings.setUiLanguage(UiLanguage.RUSSIAN) }
    private fun row() = RunningTaskEntry(1, "global-clock", "Public task", false, 7000, 1000, 30_000, 33_000)
    @Test fun `two clocks use native counters silent ongoing channel and separate immutable generation-bound controls`() {
        val row = row(); val watch = RunningTaskNotifications.build(app, row, RunningClock.STOPWATCH, false, true, 3000)
        val countdown = RunningTaskNotifications.build(app, row, RunningClock.COUNTDOWN, false, true, 3000)
        assertTrue(watch.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)); assertFalse(watch.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(countdown.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)); assertEquals(33_000L, countdown.`when`)
        assertEquals(-6000L, watch.`when`); assertEquals(30_000L, countdown.timeoutAfter)
        assertTrue(watch.flags and Notification.FLAG_ONGOING_EVENT != 0); assertTrue(watch.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, watch.visibility); assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(RunningTaskNotifications.CHANNEL).importance)
        assertFalse(manager.getNotificationChannel(RunningTaskNotifications.CHANNEL).canShowBadge())
        assertNull("Do not force orphan children into NotificationCompat silent group", watch.group)
        assertEquals(2, watch.actions.size); assertEquals(2, countdown.actions.size)
        val watchIntent = shadowOf(watch.actions[0].actionIntent).savedIntent
        val countdownIntent = shadowOf(countdown.actions[0].actionIntent).savedIntent
        assertNotEquals(watchIntent.data, countdownIntent.data)
        assertEquals(RunningClock.STOPWATCH, RunningTaskReceiver.read(watchIntent)!!.clock)
        assertEquals(1000L, RunningTaskReceiver.read(watchIntent)!!.expectedAt)
        assertTrue(shadowOf(watch.actions[0].actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertNotNull(WidgetNoteLinks.read(shadowOf(watch.contentIntent).savedIntent, 1))
    }
    @Test fun `private row has no mutating actions no deep link and a generic lock-screen version`() {
        val n = RunningTaskNotifications.build(app, row().copy(hidden = true, title = "secret should never leak"), RunningClock.STOPWATCH, false, true, 3000)
        assertEquals("Защищённая запись", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.actions.isNullOrEmpty()); assertNull(WidgetNoteLinks.read(shadowOf(n.contentIntent).savedIntent, 1))
        assertEquals("Идущие задачи", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(n.publicVersion.extras.toString().contains("secret")); assertFalse(n.extras.toString().contains("secret"))
    }
    @Test fun `malformed extra URI or missing clock generation cannot become a control command`() {
        val valid = RunningTaskReceiver.intent(app, RunningControl(2, "unicode:😀/id", RunningClock.COUNTDOWN, 999, RunningAction.PAUSE))
        assertEquals("unicode:😀/id", RunningTaskReceiver.read(valid)!!.syncId)
        assertNull(RunningTaskReceiver.read(Intent(valid).setAction("foreign")))
        assertNull(RunningTaskReceiver.read(Intent(valid).setData(valid.data!!.buildUpon().appendQueryParameter("extra", "1").build())))
        assertNull(RunningTaskReceiver.read(RunningTaskReceiver.intent(app, RunningControl(0, "s", RunningClock.STOPWATCH, 1, RunningAction.COMPLETE))))
        assertNull(RunningTaskReceiver.read(RunningTaskReceiver.intent(app, RunningControl(1, "s", RunningClock.STOPWATCH, -1, RunningAction.COMPLETE))))
    }
    @Test fun `publication restarts from durable data removes stale own notifications and leaves reminder notifications alone`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Durable task", isTask = true, taskTimerStartedAt = now - 5000,
            taskCountdownEndAt = now + 60_000).let { it.copy(id = db.noteDao().upsert(it)) }
        val other = Notification.Builder(app, "other").setSmallIcon(android.R.drawable.ic_popup_reminder).build()
        manager.notify("notels:reminder:7", 0, other); manager.notify(RunningTaskNotifications.TAG_PREFIX + "stale", 0, other)
        assertEquals(note.taskCountdownEndAt, RunningTaskNotifications.refreshNow(app))
        assertEquals(3, manager.activeNotifications.size)
        assertEquals(2, manager.activeNotifications.count { it.tag.startsWith(RunningTaskNotifications.TAG_PREFIX) })
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        RunningTaskNotifications.stop() // Simulated owner disappearance: no moving app job is needed for SystemUI.
        assertEquals(3, manager.activeNotifications.size)
        settings.setRunningTaskNotifications(false); RunningTaskNotifications.refreshNow(app)
        assertEquals(listOf("notels:reminder:7"), manager.activeNotifications.map { it.tag })
        settings.setRunningTaskNotifications(true); RunningTaskNotifications.refreshNow(app); assertEquals(3, manager.activeNotifications.size)
        assertEquals(note, db.noteDao().getById(note.id))
    }
    @Test fun `Room events mask protection and completed deletion immediately and a disabled setting cancels the observer display`() = runBlocking {
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Initial", isTask = true, taskTimerStartedAt = now - 10)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        fun own() = manager.activeNotifications.filter { it.tag.startsWith(RunningTaskNotifications.TAG_PREFIX) }
        suspend fun await(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(10) }
        RunningTaskNotifications.start(app)
        await { own().singleOrNull()?.notification?.extras?.getString(Notification.EXTRA_TITLE) == "Initial" }
        db.noteDao().update(note.copy(title = "Secret changed", isProtected = true))
        await { own().singleOrNull()?.notification?.extras?.getString(Notification.EXTRA_TITLE) == "Защищённая запись" }
        assertTrue(own().single().notification.actions.isNullOrEmpty())
        settings.setRunningTaskNotifications(false); await { own().isEmpty() }
        settings.setRunningTaskNotifications(true); await { own().size == 1 }
        db.noteDao().update(note.copy(isCompleted = true)); await { own().isEmpty() }
    }
    @Test @Config(sdk = [35]) fun `denied notification permission or muted channel never posts and granting can restore from the same record`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        db.noteDao().upsert(NoteEntity(title = "Permission", isTask = true, taskTimerStartedAt = now - 1))
        RunningTaskNotifications.refreshNow(app); assertTrue(manager.activeNotifications.isEmpty()); assertFalse(RunningTaskNotifications.canNotify(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RunningTaskNotifications.refreshNow(app); assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.single().notification.actions.all { it.isAuthenticationRequired })
        manager.deleteNotificationChannel(RunningTaskNotifications.CHANNEL)
        manager.createNotificationChannel(NotificationChannel(RunningTaskNotifications.CHANNEL, "muted", NotificationManager.IMPORTANCE_NONE))
        RunningTaskNotifications.refreshNow(app); assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test @Config(sdk = [35]) fun `permission denied owner resumes from fresh Room metadata only after an explicit foreground grant request`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        val note = NoteEntity(title = "Before grant", isTask = true, taskTimerStartedAt = now - 1)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        RunningTaskNotifications.start(app); RunningTaskNotifications.refreshNow(app)
        assertTrue(manager.activeNotifications.isEmpty())
        db.noteDao().update(note.copy(title = "Latest while denied"))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RunningTaskNotifications.request(app)
        withTimeout(10_000) { while (manager.activeNotifications.singleOrNull()?.notification?.extras?.getString(Notification.EXTRA_TITLE) != "Latest while denied") delay(10) }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RunningTaskNotifications.request(app)
        withTimeout(10_000) { while (manager.activeNotifications.isNotEmpty()) delay(10) }
        assertEquals("Latest while denied", db.noteDao().getById(note.id)!!.title)
    }

}
