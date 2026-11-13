package com.notessync.app.sync

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.Hashing
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncFileConflictTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase; private lateinit var files: File; private lateinit var source: RoomSyncDataSource
    @Before fun before() {
        files = File(app.cacheDir, "file-conflict-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = files; override fun getApplicationContext() = this }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build(); source = RoomSyncDataSource(context, db)
    }
    @After fun after() { db.close(); files.deleteRecursively() }
    @Test fun `a local conflict preserves protected content notebook tags and independent binary attachment`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Local book", updatedAt = 100))
        val note = NoteEntity(title = "Local", content = "Local body 👩🏽‍💻", notebookId = book, updatedAt = 100,
            textFormat = "local-format", tablesData = "local-table", isProtected = true, passwordHash = "hash", passwordSalt = "salt")
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val tag = db.tagDao().insert(TagEntity(name = "Local tag")); db.tagDao().attachToNote(NoteTagCrossRef(note.id, tag))
        val id = UUID.randomUUID().toString(); val bytes = byteArrayOf(1, 2, 3, 4)
        val file = File(files, "attachments/$id").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        db.attachmentDao().upsert(AttachmentEntity(syncId = id, noteId = note.id, filePath = file.path, mimeType = "audio/mp4", sizeBytes = 4))
        val original = source.getChangesSince(Long.MIN_VALUE).notes.single()
        val result = source.applyChanges(ChangeSet(notes = listOf(original.copy(title = "Remote", content = "Remote body", updatedAt = 200,
            attachments = emptyList(), tagNames = listOf("Remote tag"), notebookSyncId = null))))
        assertEquals(1, result.conflicts)
        val copied = db.noteDao().getChangedSince(Long.MIN_VALUE).single { it.id != note.id }
        assertEquals(note.content, copied.content); assertEquals(note.textFormat, copied.textFormat); assertEquals(note.tablesData, copied.tablesData)
        assertEquals(book, copied.notebookId); assertTrue(copied.dirty); assertTrue(copied.isProtected); assertEquals("hash", copied.passwordHash)
        assertEquals(listOf("Local tag"), db.tagDao().getTagNamesForNote(copied.id))
        val attached = db.attachmentDao().getByNoteId(copied.id).single()
        assertNotEquals(id, attached.syncId); assertNotEquals(file.path, attached.filePath); assertArrayEquals(bytes, File(attached.filePath).readBytes())
        assertFalse(file.exists()); assertEquals("Remote body", db.noteDao().getById(note.id)!!.content)
        val roundTrip = source.getChangesSince(Long.MIN_VALUE).notes.single { it.syncId == copied.syncId }
        assertEquals(copied.syncId, roundTrip.attachments.single().noteSyncId)
        assertEquals(Hashing.sha256Hex(bytes), roundTrip.attachments.single().sha256)
    }
    @Test fun `same attachment ID with changed bytes is requested and successful write becomes current`() = runBlocking {
        val note = NoteEntity(updatedAt = 100, dirty = false).let { it.copy(id = db.noteDao().upsert(it)) }
        val id = UUID.randomUUID().toString(); val file = File(files, "attachments/$id").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        db.attachmentDao().upsert(AttachmentEntity(syncId = id, noteId = note.id, filePath = file.path, mimeType = "image/jpeg", sizeBytes = 3))
        val bytes = byteArrayOf(4, 5, 6); val meta = AttachmentDto(id, note.syncId, "image/jpeg", 3, Hashing.sha256Hex(bytes))
        val remote = NoteDto(note.syncId, "New", "New", false, false, 0, 200, attachments = listOf(meta))
        val first = source.applyChanges(ChangeSet(notes = listOf(remote))); assertEquals(listOf(meta), first.missingAttachments)
        source.writeAttachmentBytes(meta, bytes); assertArrayEquals(bytes, file.readBytes())
        assertTrue(source.applyChanges(ChangeSet(notes = listOf(remote))).missingAttachments.isEmpty())
    }
    @Test fun `late notebook acknowledgement does not erase a newer local edit`() = runBlocking {
        val book = NotebookEntity(name = "Before", updatedAt = 100).let { it.copy(id = db.notebookDao().upsert(it)) }
        val sent = source.getChangesSince(0)
        db.notebookDao().update(book.copy(name = "Edited while exchanging", updatedAt = 200, dirty = true))
        source.markSynced(sent)
        val fresh = db.notebookDao().getById(book.id)!!
        assertTrue(fresh.dirty); assertEquals("Edited while exchanging", fresh.name)
    }
}
