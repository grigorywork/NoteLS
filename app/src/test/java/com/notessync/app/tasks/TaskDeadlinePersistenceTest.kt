package com.notessync.app.tasks

import com.notessync.app.data.backup.EncryptedBackupFixture
import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.settings.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskDeadlinePersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL) }
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `schema sixteen migration adds no implicit deadline and preserves existing reminder timer and day metadata`() = runBlocking {
        val name = "schemas/com.notessync.app.data.local.AppDatabase/16.json"
        val schema = JSONObject(listOf(File(name), File("app/$name")).first { it.isFile }.readText()).getJSONObject("database")
        val path = app.getDatabasePath("notessync.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(notes)", null).use { columns -> while (columns.moveToNext()) {
                if (columns.getInt(3) != 0 || columns.getInt(5) != 0) {
                    val field = columns.getString(1)
                    if (columns.getString(2) == "TEXT") values.put(field, when (field) { "syncId" -> "legacy-deadline"; "title" -> "Legacy"; "content" -> "Original 😀"; else -> "" })
                    else values.put(field, if (field == "id") 1L else 0L)
                }
            } }
            values.put("isTask", 1); values.put("dayKey", 20_000L); values.put("reminderAt", 777L)
            values.put("taskCountdownEndAt", 888L); values.put("taskElapsedMillis", 345L)
            old.insertOrThrow("notes", null, values); old.version = 16
        }
        val db = AppDatabase.get(app); val note = db.noteDao().getById(1)!!
        assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version); assertNull(note.taskDueAt)
        assertEquals(777L, note.reminderAt); assertEquals(888L, note.taskCountdownEndAt); assertEquals(345L, note.taskElapsedMillis)
        assertEquals(20_000L, note.dayKey); assertEquals("Original 😀", note.content)
    }
    @Test fun `deadline backup and wire roundtrips preserve absence versus explicit clear and purging removes the deadline`() = runBlocking {
        val db = AppDatabase.get(app)
        val original = NoteEntity(title = "Deadline", isTask = true, taskDueAt = 1_234_567L, reminderAt = 2_345_678L, updatedAt = 1_000L)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val output = ByteArrayOutputStream(); EncryptedBackupFixture.createBackup(app, output)
        val zip = File(app.cacheDir, "deadline.zip").apply { writeBytes(output.toByteArray()) }
        db.noteDao().update(original.copy(taskDueAt = null, reminderAt = null))
        assertTrue(EncryptedBackupFixture.restore(app, Uri.fromFile(zip)).isSuccess); assertEquals(original, db.noteDao().getById(original.id))
        val source = RoomSyncDataSource(app, db)
        val message = SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(source.getChangesSince(0)))) as SyncMessage.Changes
        val dto = message.set.notes.single(); assertEquals(original.taskDueAt, dto.taskDeadline!!.at)
        val peerDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val peer = RoomSyncDataSource(app, peerDb); peer.applyChanges(message.set)
            val first = peerDb.noteDao().getBySyncId(dto.syncId)!!; assertEquals(original.taskDueAt, first.taskDueAt)
            val oldWire = JSONObject(String(SyncMessage.encode(SyncMessage.Changes(ChangeSet(notes = listOf(dto.copy(updatedAt = 1_001))))))).apply {
                getJSONObject("set").getJSONArray("notes").getJSONObject(0).remove("taskDeadline")
            }.toString()
            val legacy = SyncMessage.decode(oldWire.toByteArray()) as SyncMessage.Changes
            assertNull(legacy.set.notes.single().taskDeadline); peer.applyChanges(legacy.set)
            assertEquals(original.taskDueAt, peerDb.noteDao().getBySyncId(dto.syncId)!!.taskDueAt)
            val cleared = dto.copy(taskDeadline = TaskDeadlineDto(null), updatedAt = 1_002)
            val encoded = SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(ChangeSet(notes = listOf(cleared))))) as SyncMessage.Changes
            assertNotNull(encoded.set.notes.single().taskDeadline); assertNull(encoded.set.notes.single().taskDeadline!!.at)
            peer.applyChanges(encoded.set); val current = peerDb.noteDao().getBySyncId(dto.syncId)!!
            assertNull(current.taskDueAt); assertEquals(original.reminderAt, current.reminderAt)
            assertTrue(runCatching { peer.applyChanges(ChangeSet(notes = listOf(dto.copy(taskDeadline = TaskDeadlineDto(Long.MAX_VALUE), updatedAt = 1_003)))) }.isFailure)
            assertEquals(current, peerDb.noteDao().getBySyncId(dto.syncId))
            val actions = NoteActions(app, peerDb); actions.apply(setOf(current.id), NoteActions.Action.TRASH); actions.apply(setOf(current.id), NoteActions.Action.PURGE)
            assertNull(peerDb.noteDao().getById(current.id)!!.taskDueAt); assertEquals("", peerDb.noteDao().getById(current.id)!!.title)
        } finally { peerDb.close() }
    }
}
