package com.notessync.app.sync

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.Hashing
import com.notessync.app.sync.core.SyncLimits
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncRoomSafetyTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var files: File
    private lateinit var source: RoomSyncDataSource
    @Before fun before() {
        files = File(app.cacheDir, "sync-safety-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = files; override fun getApplicationContext() = this }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build(); source = RoomSyncDataSource(context, db)
    }
    @After fun after() { db.close(); files.deleteRecursively() }
    @Test fun `invalid attachment owner rejects whole incoming package before notebook or note mutation`() = runBlocking {
        val meta = AttachmentDto(UUID.randomUUID().toString(), "wrong-parent", "image/jpeg", 1, Hashing.sha256Hex(byteArrayOf(1)))
        val set = ChangeSet(notebooks = listOf(NotebookDto("book", "Remote book", false, 1)), notes = listOf(
            NoteDto("note", "Remote", "body", false, false, 0, 1, attachments = listOf(meta))))
        assertTrue(runCatching { source.applyChanges(set) }.isFailure)
        assertNull(db.notebookDao().getBySyncId("book")); assertNull(db.noteDao().getBySyncId("note"))
    }
    @Test fun `sparse oversized file is refused before reading and corrupt writes preserve existing bytes`() = runBlocking {
        val note = NoteEntity().let { it.copy(id = db.noteDao().upsert(it)) }; val id = UUID.randomUUID().toString()
        val file = File(files, "attachments/$id").apply { parentFile!!.mkdirs() }
        RandomAccessFile(file, "rw").use { it.setLength(SyncLimits.ATTACHMENT_BYTES + 1) }
        db.attachmentDao().upsert(AttachmentEntity(noteId = note.id, syncId = id, filePath = file.path, mimeType = "image/jpeg", sizeBytes = file.length()))
        assertTrue(runCatching { source.readAttachmentBytes(id) }.isFailure)
        file.writeBytes(byteArrayOf(7))
        val meta = AttachmentDto(id, note.syncId, "image/jpeg", 2, Hashing.sha256Hex(byteArrayOf(1, 2)))
        assertTrue(runCatching { source.writeAttachmentBytes(meta, byteArrayOf(9, 9)) }.isFailure)
        assertArrayEquals(byteArrayOf(7), file.readBytes()); assertEquals(SyncLimits.ATTACHMENT_BYTES + 1, db.attachmentDao().getBySyncId(id)!!.sizeBytes)
    }
    @Test fun `already cancelled import cannot mutate notes or notebooks`() = runBlocking {
        val set = ChangeSet(notebooks = listOf(NotebookDto("cancel-book", "Cancelled", false, 1)), notes = listOf(
            NoteDto("cancel-note", "Cancelled", "body", false, false, 0, 1)))
        val importing = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            source.applyChanges(set)
        }
        importing.join()
        assertTrue(importing.isCancelled)
        assertNull(db.notebookDao().getBySyncId("cancel-book")); assertNull(db.noteDao().getBySyncId("cancel-note"))
    }
    @Test fun `legacy empty identities are repaired without replacing notes books tags or attachment files`() = runBlocking {
        val book = db.notebookDao().upsert(com.notessync.app.data.local.entity.NotebookEntity(syncId = "", name = "Legacy book", dirty = false, updatedAt = 200))
        val note = NoteEntity(syncId = "", title = "Legacy", content = "Body 👩🏽‍💻", notebookId = book, dirty = false, updatedAt = 200)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val tag = db.tagDao().insert(com.notessync.app.data.local.entity.TagEntity(name = "Legacy tag"))
        db.tagDao().attachToNote(com.notessync.app.data.local.entity.NoteTagCrossRef(noteId = note.id, tagId = tag))
        repeat(2) { index ->
            val file = File(files, "attachments/legacy-$index").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(index.toByte())) }
            db.attachmentDao().upsert(AttachmentEntity(syncId = "", noteId = note.id, filePath = file.path, mimeType = "image/jpeg", sizeBytes = 1))
        }
        source.ensureSyncIds()
        val repaired = db.noteDao().getById(note.id)!!; val repairedBook = db.notebookDao().getById(book)!!
        assertEquals(note.content, repaired.content); assertEquals(book, repaired.notebookId)
        assertEquals(listOf("Legacy tag"), db.tagDao().getTagNamesForNote(note.id))
        UUID.fromString(repaired.syncId); UUID.fromString(repairedBook.syncId)
        val attachments = db.attachmentDao().getByNoteId(note.id)
        assertEquals(2, attachments.size); assertEquals(2, attachments.map { it.syncId }.distinct().size)
        attachments.forEach { UUID.fromString(it.syncId); assertTrue(File(it.filePath).isFile) }
        assertArrayEquals(byteArrayOf(0), source.readAttachmentBytes(attachments.single { it.filePath.endsWith("legacy-0") }.syncId))
        val changed = source.getChangesSince(Long.MAX_VALUE)
        assertEquals(1, changed.notes.size); assertEquals(2, changed.notes.single().attachments.size)
        SyncLimits.changes(changed)
        source.ensureSyncIds()
        assertEquals(repaired, db.noteDao().getById(note.id)); assertEquals(repairedBook, db.notebookDao().getById(book))
        assertEquals(attachments.sortedBy { it.id }, db.attachmentDao().getByNoteId(note.id).sortedBy { it.id })
    }
}
