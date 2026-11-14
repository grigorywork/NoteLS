package com.notessync.app.ui.notes.audit

import android.app.AlarmManager
import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.*
import java.util.UUID
import com.notessync.app.ui.notes.EditorTaskTiming
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real committed target plus controlled editor-scope closure; not a physical Back press or phone measurement. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorAlarmFreshBindingRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); app.createReminderChannel() }
    @After fun after() {
        scope.cancel(); dispatcher.scheduler.runCurrent()
        val alarms = app.getSystemService(AlarmManager::class.java)
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { pending -> alarms.cancel(pending) } }
        AppDatabase.closeInstance()
    }
    private suspend fun staleCompletion(timer: Boolean) {
        val note = NoteEntity(title = "Old fixture", isTask = true).let { it.copy(id = db.noteDao().upsert(it)) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        lateinit var timing: EditorTaskTiming
        timing = EditorTaskTiming(app, scope, { true }, { false }, { false }, { note.id },
            persist = {
                val fresh = db.noteDao().getById(note.id)!!
                db.noteDao().update(fresh.copy(reminderAt = timing.reminderAt, taskCountdownEndAt = timing.taskCountdownEndAt)); true
            }, report = {}, parentSyncId = { note.syncId },
            alarms = GatedAlarmCommands(RoomNoteAlarmCommands(app)) { entered.complete(Unit); release.await() })
        val first = System.currentTimeMillis() + 3600000
        if (timer) timing.startCountdownUntil(first) else timing.setReminder(first)
        withContext(Dispatchers.IO) { withTimeout(15000) { entered.await() } }
        val current = db.noteDao().getById(note.id)!!
        val next = first + 3600000
        val changed = if (timer) current.copy(taskCountdownEndAt = next)
            else current.copy(syncId = UUID.randomUUID().toString(), reminderAt = next)
        db.noteDao().update(changed)
        if (timer) assertTrue(ReminderScheduler.scheduleTimer(app, changed.id, changed.title, next, changed.syncId))
        else assertTrue(ReminderScheduler.schedule(app, changed.id, changed.title, next, changed.syncId))
        val alarms = app.getSystemService(AlarmManager::class.java)
        assertEquals(next, shadowOf(alarms).scheduledAlarms.single().triggerAtMs) // New writer registered its committed target.
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        withContext(Dispatchers.IO) { withTimeout(15000) { timing.awaitIdle() } }
        assertEquals("Stale editor metadata completion must not replace a newer DB target", next,
            shadowOf(alarms).scheduledAlarms.single().triggerAtMs)
        assertEquals(changed.syncId, shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent.getStringExtra(ReminderReceiver.EXTRA_SYNC_ID))
    }
    @Test fun `old reminder cannot overwrite a new identity sharing its numeric ID`() = runTest(dispatcher) { staleCompletion(false) }
    @Test fun `old countdown cannot overwrite a newer committed deadline of the same identity`() = runTest(dispatcher) { staleCompletion(true) }
}
