package com.notessync.app.ui.notes.audit

import android.app.AlarmManager
import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.*
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
class EditorAlarmLifetimeRegressionTest {
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
    @Test fun `committed reminder survives editor scope closing while notification metadata is loading`() = runTest(dispatcher) {
        val note = NoteEntity(title = "Fixture task", isTask = true).let { it.copy(id = db.noteDao().upsert(it)) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        lateinit var timing: EditorTaskTiming
        timing = EditorTaskTiming(app, scope, { true }, { false }, { false }, { note.id },
            persist = {
                val fresh = db.noteDao().getById(note.id)!!
                db.noteDao().update(fresh.copy(reminderAt = timing.reminderAt)); true
            }, report = {}, parentSyncId = { note.syncId },
            alarms = GatedAlarmCommands(RoomNoteAlarmCommands(app)) { entered.complete(Unit); release.await() })
        val at = System.currentTimeMillis() + 3600000
        timing.setReminder(at)
        withContext(Dispatchers.IO) { withTimeout(15000) { entered.await() } }
        assertEquals(at, db.noteDao().getById(note.id)!!.reminderAt) // The note write really committed before closing.
        scope.cancel(); release.complete(Unit); dispatcher.scheduler.runCurrent()
        withContext(Dispatchers.IO) { withTimeout(15000) { timing.awaitIdle() } }
        val alarms = app.getSystemService(AlarmManager::class.java)
        assertEquals("Closing the editor must not lose a committed requested reminder", 1, shadowOf(alarms).scheduledAlarms.size)
    }
}
