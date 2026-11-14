package com.notessync.app.sync.audit

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.reminders.*
import com.notessync.app.sync.android.RoomSyncDataSource
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real transaction rollback/cancellation controls, not sleeps, network exchanges or phone benchmarks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncAlarmCommitSafetyTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val recovery get() = app.getSharedPreferences("alarm_recovery", 0)
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); app.createReminderChannel(); recovery.edit().clear().commit()
    }
    @After fun after() {
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { pending -> alarms.cancel(pending) } }
        AppDatabase.closeInstance()
    }
    private suspend fun save(note: NoteEntity) = note.copy(id = db.noteDao().upsert(note))
    @Test fun `ignored older remote version leaves current alarm and recovery state untouched`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000
        val note = save(NoteEntity(title = "New local version", reminderAt = at, dirty = false))
        assertTrue(ReminderScheduler.schedule(app, note.id, note.title, at, note.syncId))
        val source = RoomSyncDataSource(app, db); val before = source.getChangesSince(Long.MIN_VALUE)
        val result = source.applyChanges(before.copy(notes = before.notes.map { it.copy(updatedAt = it.updatedAt - 1, reminderAt = at + 5000) }))
        assertEquals(0, result.appliedNotes)
        assertEquals(at, shadowOf(alarms).scheduledAlarms.single().triggerAtMs)
        assertFalse(recovery.getBoolean("pending", false))
    }
    @Test fun `ordinary peer text without clock targets does not request alarm recovery or disturb a different note`() = runBlocking {
        val note = save(NoteEntity(title = "Plain peer", content = "Body without targets", dirty = false))
        val source = RoomSyncDataSource(app, db); val remote = source.getChangesSince(Long.MIN_VALUE)
        db.openHelper.writableDatabase.execSQL("DELETE FROM notes")
        val at = System.currentTimeMillis() + 3600000
        val control = save(NoteEntity(id = 10, title = "Unrelated alarm", reminderAt = at, dirty = false))
        assertTrue(ReminderScheduler.schedule(app, control.id, control.title, at, control.syncId))
        assertEquals(1, source.applyChanges(remote).appliedNotes)
        assertEquals(note.content, db.noteDao().getBySyncId(note.syncId)!!.content)
        assertEquals(at, shadowOf(alarms).scheduledAlarms.single().triggerAtMs)
        assertFalse(recovery.getBoolean("pending", false))
    }
    @Test fun `later rollback registers only earlier committed targets and preserves unrelated alarm`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000
        val one = save(NoteEntity(title = "Committed peer", reminderAt = at, dirty = false))
        val rejected = save(NoteEntity(title = "Rejected peer", reminderAt = at + 1000, dirty = false))
        val source = RoomSyncDataSource(app, db); val initial = source.getChangesSince(Long.MIN_VALUE)
        val remote = initial.copy(notes = listOf(initial.notes.single { it.syncId == one.syncId }, initial.notes.single { it.syncId == rejected.syncId }))
        db.openHelper.writableDatabase.execSQL("DELETE FROM notes")
        val control = save(NoteEntity(id = 10, title = "Unrelated", reminderAt = at + 2000, dirty = false))
        assertTrue(ReminderScheduler.schedule(app, control.id, control.title, control.reminderAt!!, control.syncId))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_peer BEFORE INSERT ON notes WHEN NEW.title = 'Rejected peer' BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END")
        assertTrue(runCatching { source.applyChanges(remote) }.isFailure)
        val committed = db.noteDao().getBySyncId(one.syncId)!!
        assertNull(db.noteDao().getBySyncId(rejected.syncId))
        val entries = shadowOf(alarms).scheduledAlarms
        assertEquals(2, entries.size)
        assertEquals(setOf(control.id, committed.id), entries.map { shadowOf(it.operation).savedIntent.getLongExtra(ReminderReceiver.EXTRA_NOTE_ID, -1) }.toSet())
        assertTrue(recovery.getBoolean("pending", false))
    }
    @Test fun `cancellation inside an accepted SQL write still publishes its committed target on the supplied database`() = runBlocking {
        val at = System.currentTimeMillis() + 3600000
        val note = save(NoteEntity(title = "Peer fixture", reminderAt = at, dirty = false))
        val remote = RoomSyncDataSource(app, db).getChangesSince(Long.MIN_VALUE)
        db.openHelper.writableDatabase.execSQL("DELETE FROM notes") // The global source is empty; reconciliation must use target.
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val gate = AtomicBoolean(true)
        val target = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (sql.trimStart().startsWith("INSERT", true) && sql.contains("`notes`", true) && gate.compareAndSet(true, false)) {
                    entered.complete(Unit); check(release.await(15, TimeUnit.SECONDS))
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val exchange = launch(Dispatchers.IO) { RoomSyncDataSource(app, target).applyChanges(remote) }
            try {
                withTimeout(15000) { entered.await() }
                exchange.cancel(); release.countDown(); exchange.join()
                assertTrue(exchange.isCancelled)
                val stored = target.noteDao().getBySyncId(note.syncId)!!
                assertEquals(at, stored.reminderAt)
                val delivery = shadowOf(alarms).scheduledAlarms.single()
                assertEquals(at, delivery.triggerAtMs)
                assertEquals(stored.syncId, shadowOf(delivery.operation).savedIntent.getStringExtra(ReminderReceiver.EXTRA_SYNC_ID))
            } finally { release.countDown(); exchange.cancelAndJoin() }
        } finally { target.close() }
    }
}
