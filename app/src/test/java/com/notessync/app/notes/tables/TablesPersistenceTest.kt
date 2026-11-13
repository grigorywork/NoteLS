package com.notessync.app.notes.tables

import com.notessync.app.data.backup.EncryptedBackupFixture
import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
class TablesPersistenceTest {
    @Before fun setup() { AppDatabase.closeInstance(); RuntimeEnvironment.getApplication().deleteDatabase("notessync.db") }
    @After fun teardown() { AppDatabase.closeInstance() }
    private fun data() = NoteTables().add("one", 2, 3).editCell(TableCellRef("one", 1, 2), "Сохранить 😀\nвсё")
        .resizeColumn("one", 1, 320).resizeRow("one", 0, 128).encode()

    @Test fun `real database twelve migrates text and formatting without inventing tables`() = runBlocking {
        val relative = "schemas/com.notessync.app.data.local.AppDatabase/12.json"
        val schema = JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val app = RuntimeEnvironment.getApplication(); val path = app.getDatabasePath("notessync.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(notes)", null).use { columns ->
                while (columns.moveToNext()) if (columns.getInt(3) != 0 || columns.getInt(5) != 0) {
                    val name = columns.getString(1)
                    if (columns.getString(2) == "TEXT") values.put(name, when (name) {
                        "title" -> "Legacy"; "content" -> "Text"; "syncId" -> "old"; "textFormat" -> "kept-format"; else -> ""
                    }) else values.put(name, if (name == "id") 1L else 0L)
                }
            }
            old.insertOrThrow("notes", null, values); old.version = 12
        }
        val note = AppDatabase.get(app).noteDao().getById(1)!!
        assertEquals("Text", note.content); assertEquals("kept-format", note.textFormat); assertEquals("", note.tablesData)
        assertEquals(AppDatabase.VERSION, AppDatabase.get(app).openHelper.readableDatabase.version)
    }
    @Test fun `table cells and dimensions survive backup restore and peer exchange`() = runBlocking {
        val app = RuntimeEnvironment.getApplication(); val db = AppDatabase.get(app)
        val raw = data(); val id = db.noteDao().upsert(NoteEntity(title = "Table", tablesData = raw, dirty = false))
        // Photo import/removal must not overwrite a newer document with a stale entity copy.
        val edited = db.noteDao().getById(id)!!.copy(content = "Latest body", tablesData = raw)
        db.noteDao().update(edited)
        db.noteDao().markContentChanged(id, edited.updatedAt + 1)
        val marked = db.noteDao().getById(id)!!
        assertEquals(edited.content, marked.content); assertEquals(raw, marked.tablesData); assertTrue(marked.dirty)
        val bytes = ByteArrayOutputStream(); EncryptedBackupFixture.createBackup(app, bytes)
        val packed = File(app.cacheDir, "tables-backup.zip").apply { writeBytes(bytes.toByteArray()) }
        db.noteDao().update(db.noteDao().getById(id)!!.copy(tablesData = ""))
        assertTrue(EncryptedBackupFixture.restore(app, Uri.fromFile(packed)).isSuccess)
        assertEquals(raw, db.noteDao().getById(id)!!.tablesData)
        val peer = RoomSyncDataSource(app, db); val dto = peer.getChangesSince(0).notes.single()
        assertEquals(raw, dto.tablesData)
        val received = SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(
            ChangeSet(notes = listOf(dto.copy(syncId = "new-peer")))))) as SyncMessage.Changes
        peer.applyChanges(received.set)
        assertEquals(raw, db.noteDao().getBySyncId("new-peer")!!.tablesData)
    }
    @Test fun `legacy peer omission preserves tables but explicit clear removes them`() = runBlocking {
        val app = RuntimeEnvironment.getApplication(); val db = AppDatabase.get(app); val raw = data()
        val id = db.noteDao().upsert(NoteEntity(syncId = "local", title = "t", tablesData = raw, updatedAt = 10, dirty = false))
        val old = Json.decodeFromString<NoteDto>("""{"syncId":"local","title":"old-client edit","content":"body","favorite":false,"deleted":false,"createdAt":1,"updatedAt":20}""")
        assertNull(old.tablesData)
        val peer = RoomSyncDataSource(app, db)
        peer.applyChanges(ChangeSet(notes = listOf(old)))
        assertEquals(raw, db.noteDao().getById(id)!!.tablesData)
        assertEquals("body", db.noteDao().getById(id)!!.content)
        peer.applyChanges(ChangeSet(notes = listOf(old.copy(tablesData = "", updatedAt = 30))))
        assertEquals("", db.noteDao().getById(id)!!.tablesData)
    }
    @Test fun `table-only conflict keeps the complete local table copy`() {
        val local = NoteDto("same", "title", "", false, false, 1, 10, tablesData = data())
        val result = ConflictResolver.resolve(local, local.copy(updatedAt = 20, tablesData = ""), true)
            as ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy
        assertEquals(local.tablesData, result.conflictCopy.tablesData)
        assertTrue(ConflictResolver.resolve(local, local.copy(updatedAt = 20, tablesData = null), true) is ConflictResolver.Resolution.ApplyRemote)
    }
}
