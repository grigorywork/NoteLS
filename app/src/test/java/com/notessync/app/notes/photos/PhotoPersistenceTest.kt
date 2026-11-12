package com.notessync.app.notes.photos

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.Hashing
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
class PhotoPersistenceTest {
    @Before fun setup() { AppDatabase.closeInstance(); RuntimeEnvironment.getApplication().deleteDatabase("notessync.db") }
    @After fun teardown() { AppDatabase.closeInstance() }
    private val photoId = "11111111-1111-1111-1111-111111111111"

    @Test fun `real schema thirteen migrates photos without modifying original bytes`() = runBlocking {
        val app = RuntimeEnvironment.getApplication(); val file = File(app.filesDir, "legacy-photo").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val relative = "schemas/com.notessync.app.data.local.AppDatabase/13.json"
        val schema = JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val path = app.getDatabasePath("notessync.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i); val table = e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            for (table in listOf("notes", "attachments")) {
                val values = ContentValues()
                old.rawQuery("PRAGMA table_info($table)", null).use { columns ->
                    while (columns.moveToNext()) if (columns.getInt(3) != 0 || columns.getInt(5) != 0) {
                        val name = columns.getString(1)
                        if (columns.getString(2) == "TEXT") values.put(name, when (name) {
                            "title" -> "Legacy"; "content" -> "Body"; "syncId" -> if (table == "attachments") photoId else "note"
                            "filePath" -> file.path; "mimeType" -> "image/jpeg"; else -> ""
                        }) else values.put(name, when (name) { "id", "noteId" -> 1L; "sizeBytes" -> 3L; else -> 0L })
                    }
                }
                old.insertOrThrow(table, null, values)
            }
            old.version = 13
        }
        val db = AppDatabase.get(app); val photo = db.attachmentDao().getBySyncId(photoId)!!
        assertEquals(240, photo.displayWidthDp); assertEquals(180, photo.displayHeightDp); assertEquals(0L, photo.previewRevision)
        assertEquals("Body", db.noteDao().getById(1)!!.content)
        assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
    @Test fun `display geometry and unchanged photo survive backup restore and protocol exchange`() = runBlocking {
        val app = RuntimeEnvironment.getApplication(); val db = AppDatabase.get(app)
        val bytes = byteArrayOf(1, 3, 5, 7)
        val file = File(app.filesDir, "attachments/$photoId").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val noteId = db.noteDao().upsert(NoteEntity(title = "Photo"))
        db.attachmentDao().upsert(AttachmentEntity(syncId = photoId, noteId = noteId, filePath = file.path,
            mimeType = "image/jpeg", sizeBytes = bytes.size.toLong(), displayWidthDp = 432, displayHeightDp = 288))
        val archive = ByteArrayOutputStream(); BackupManager.createBackup(app, archive)
        val packed = File(app.cacheDir, "photo-size.zip").apply { writeBytes(archive.toByteArray()) }
        db.attachmentDao().updateDisplaySize(db.attachmentDao().getBySyncId(photoId)!!.id, noteId, 144, 144)
        assertTrue(BackupManager.restore(app, Uri.fromFile(packed)).isSuccess)
        val restored = db.attachmentDao().getBySyncId(photoId)!!
        assertEquals(432, restored.displayWidthDp); assertEquals(288, restored.displayHeightDp)
        assertArrayEquals(bytes, File(restored.filePath).readBytes())
        val source = RoomSyncDataSource(app, db)
        val message = SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(source.getChangesSince(0)))) as SyncMessage.Changes
        val dto = message.set.notes.single(); val meta = dto.attachments.single()
        assertEquals(432, meta.displayWidthDp); assertEquals(288, meta.displayHeightDp)
        assertEquals(Hashing.sha256Hex(bytes), meta.sha256)
        val peerFiles = File(app.cacheDir, "peer-files").apply { mkdirs() }
        val peerContext = object : ContextWrapper(app) { override fun getFilesDir(): File = peerFiles }
        val peerDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val peerSource = RoomSyncDataSource(peerContext, peerDb)
            peerSource.applyChanges(message.set)
            assertFalse(peerSource.hasAttachmentBytes(meta.syncId))
            val transferred = source.readAttachmentBytes(meta.syncId)!!
            assertArrayEquals(bytes, transferred)
            peerSource.writeAttachmentBytes(meta, transferred)
            assertTrue(peerSource.hasAttachmentBytes(meta.syncId))
            val peer = peerDb.noteDao().getBySyncId(dto.syncId)!!
            val received = peerDb.attachmentDao().getByNoteId(peer.id).single()
            assertEquals(432, received.displayWidthDp); assertEquals(288, received.displayHeightDp)
            assertArrayEquals(bytes, File(received.filePath).readBytes())
        } finally { peerDb.close() }
    }
    @Test fun `legacy omission retains local sizes and file arrival increments preview revision`() = runBlocking {
        val app = RuntimeEnvironment.getApplication(); val db = AppDatabase.get(app); val source = RoomSyncDataSource(app, db)
        val bytes = byteArrayOf(4, 3, 2, 1)
        val meta = AttachmentDto(photoId, "new", "image/jpeg", bytes.size.toLong(), Hashing.sha256Hex(bytes), 480, 360)
        val dto = NoteDto("new", "n", "", false, false, 1, 10, attachments = listOf(meta))
        source.applyChanges(ChangeSet(notes = listOf(dto)))
        val before = db.attachmentDao().getBySyncId(photoId)!!
        assertEquals(0L, before.previewRevision)
        source.writeAttachmentBytes(meta, bytes)
        val arrived = db.attachmentDao().getBySyncId(photoId)!!
        assertEquals(1L, arrived.previewRevision); assertArrayEquals(bytes, File(arrived.filePath).readBytes())
        source.applyChanges(ChangeSet(notes = listOf(dto.copy(updatedAt = 20,
            attachments = listOf(meta.copy(displayWidthDp = null, displayHeightDp = null))))))
        val retained = db.attachmentDao().getBySyncId(photoId)!!
        assertEquals(before.id, retained.id); assertEquals(480, retained.displayWidthDp); assertEquals(360, retained.displayHeightDp)
        assertEquals(1L, retained.previewRevision)
        // A session may still request its earlier snapshot after metadata changes removed the row.
        db.attachmentDao().deleteByNoteId(retained.noteId)
        assertTrue(source.hasAttachmentBytes(photoId))
        assertArrayEquals(bytes, source.readAttachmentBytes(photoId))
    }
}
