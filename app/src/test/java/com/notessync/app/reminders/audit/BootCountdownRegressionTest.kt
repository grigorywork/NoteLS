package com.notessync.app.reminders.audit

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.util.concurrent.TimeUnit

/** Simulated boot broadcast on a Native Room fixture; proves registration logic, not a physical reboot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BootCountdownRegressionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); app.createReminderChannel() }
    @After fun after() {
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { operation -> alarms.cancel(operation) } }
        AppDatabase.closeInstance()
    }
    @Test fun `boot restores both ordinary reminder and running task countdown`() = runBlocking {
        val db = AppDatabase.get(app); val at = System.currentTimeMillis() + 3600000
        val reminder = db.noteDao().upsert(NoteEntity(title = "Reminder fixture", reminderAt = at))
        val timer = db.noteDao().upsert(NoteEntity(title = "Timer fixture", isTask = true,
            taskCountdownMillis = 3600000, taskCountdownEndAt = at))
        val receiver = BootReceiver()
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED))
        try {
            app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(receiver).wentAsync())
            val pending = requireNotNull(shadowOf(receiver).originalPendingResult)
            Shadow.extract<ShadowBroadcastPendingResult>(pending).future.get(10, TimeUnit.SECONDS)
            val intents = shadowOf(alarms).scheduledAlarms.map { shadowOf(it.operation).savedIntent }
            assertTrue("Ordinary reminder is a positive control for boot completion", intents.any {
                it.getLongExtra(ReminderReceiver.EXTRA_NOTE_ID, -1) == reminder && !it.getBooleanExtra(ReminderReceiver.EXTRA_IS_TIMER, false)
            })
            assertTrue("Running countdown must also be restored after boot", intents.any {
                it.getLongExtra(ReminderReceiver.EXTRA_NOTE_ID, -1) == timer && it.getBooleanExtra(ReminderReceiver.EXTRA_IS_TIMER, false)
            })
        } finally { app.unregisterReceiver(receiver) }
    }
}
