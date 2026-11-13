package com.notessync.app.sync

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.Hashing
import com.notessync.app.sync.core.SyncLimits
import com.notessync.app.sync.protocol.AttachmentDto
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StreamingRoomTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var files: File
    private lateinit var context: ContextWrapper
    private val queries = ConcurrentLinkedQueue<String>()
    @Before fun before() {
        files = File(app.cacheDir, "stream-room-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) { override fun getFilesDir() = files; override fun getApplicationContext() = this }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
    }
    @After fun after() { db.close(); files.deleteRecursively() }
    private suspend fun parent(): NoteEntity = NoteEntity().let { it.copy(id = db.noteDao().upsert(it)) }
    private fun meta(note: NoteEntity, bytes: ByteArray, id: String = UUID.randomUUID().toString()) =
        AttachmentDto(id, note.syncId, "audio/mp4", bytes.size.toLong(), Hashing.sha256Hex(bytes))
    private fun incoming() = File(files, "attachments/.sync-incoming")

    @Test fun `disk receiver stages bytes privately and atomically publishes only verified content`() = runBlocking {
        val note = parent(); val block = ByteArray(64 * 1024) { (it % 251).toByte() }; val blocks = 65
        val digest = MessageDigest.getInstance("SHA-256"); repeat(blocks) { digest.update(block) }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val meta = meta(note, byteArrayOf()).copy(sizeBytes = blocks.toLong() * block.size, sha256 = sha)
        val target = File(files, "attachments/${meta.syncId}").apply { parentFile!!.mkdirs(); writeText("old") }
        val rowId = db.attachmentDao().upsert(AttachmentEntity(noteId = note.id, syncId = meta.syncId,
            filePath = target.path, mimeType = meta.mimeType, sizeBytes = 3))
        val stale = File(incoming().apply { mkdirs() }, "receive-abandoned.tmp").apply { writeText("stale"); setLastModified(1L) }
        val recent = File(incoming(), "receive-recent.tmp").apply { writeText("recent"); setLastModified(System.currentTimeMillis()) }
        val source = RoomSyncDataSource(context, db)
        source.receiveAttachment(meta).use { sink ->
            repeat(blocks) { sink.output.write(block) }
            assertEquals("old", target.readText()); assertEquals(3L, db.attachmentDao().getBySyncId(meta.syncId)!!.sizeBytes)
            assertFalse(stale.exists()); assertTrue(recent.exists())
            sink.commit()
        }
        assertEquals(meta.sizeBytes, target.length()); assertEquals(meta.sha256, target.inputStream().use { Hashing.sha256Hex(it) })
        val row = db.attachmentDao().getBySyncId(meta.syncId)!!
        assertEquals(rowId, row.id); assertEquals(meta.sizeBytes, row.sizeBytes); assertEquals(1L, row.previewRevision)
        assertEquals(listOf(recent.name), incoming().listFiles()!!.map { it.name })
        source.openAttachment(meta.syncId)!!.use { assertEquals(meta.sizeBytes, it.sizeBytes); assertEquals(meta.sha256, Hashing.sha256Hex(it.input)) }
    }
    @Test fun `bad aborted oversized and purged stream writes preserve the previous file and clean staging`() = runBlocking {
        val note = parent(); val bytes = byteArrayOf(1, 2, 3); val meta = meta(note, bytes)
        val target = File(files, "attachments/${meta.syncId}").apply { parentFile!!.mkdirs(); writeText("old") }
        db.attachmentDao().upsert(AttachmentEntity(noteId = note.id, syncId = meta.syncId, filePath = target.path, mimeType = meta.mimeType, sizeBytes = 3))
        val source = RoomSyncDataSource(context, db)
        for (bad in listOf(byteArrayOf(1), byteArrayOf(9, 9, 9), byteArrayOf(1, 2, 3, 4))) {
            assertTrue(runCatching { source.receiveAttachment(meta).use { it.output.write(bad); it.commit() } }.isFailure)
            assertEquals("old", target.readText()); assertTrue(incoming().listFiles().orEmpty().isEmpty())
        }
        source.receiveAttachment(meta).use { it.output.write(1) } // Session cancellation/EOF closes without commit.
        assertEquals("old", target.readText()); assertTrue(incoming().listFiles().orEmpty().isEmpty())
        assertTrue(runCatching { source.receiveAttachment(meta.copy(sizeBytes = SyncLimits.ATTACHMENT_BYTES + 1)) }.isFailure)
        val other = parent()
        source.receiveAttachment(meta.copy(noteSyncId = other.syncId)).use { sink -> sink.output.write(bytes); sink.commit() }
        assertEquals("old", target.readText()); assertTrue(incoming().listFiles().orEmpty().isEmpty())
        source.receiveAttachment(meta).use { sink ->
            sink.output.write(bytes)
            db.noteDao().update(note.copy(isPurged = true))
            sink.commit()
        }
        assertEquals("old", target.readText()); assertTrue(incoming().listFiles().orEmpty().isEmpty())
        assertEquals(0L, db.attachmentDao().getBySyncId(meta.syncId)!!.previewRevision)
    }
    @Test fun `SQL metadata snapshot batches relations and hashes after releasing its transaction`() = runBlocking {
        val count = 451; val special = setOf(0, 199, 200, 450)
        val expected = mutableMapOf<String, Pair<String, String?>>()
        val bytes = byteArrayOf(4, 5, 6); val hash = Hashing.sha256Hex(bytes)
        db.withTransaction {
            val book = db.notebookDao().upsert(NotebookEntity(name = "Stable book", syncId = "book", dirty = false, updatedAt = 1))
            val tag = db.tagDao().insert(TagEntity(name = "Tag"))
            repeat(count) { i ->
                val note = NoteEntity(syncId = "note-$i", title = "Title $i", content = "Body $i", notebookId = if (i % 2 == 0) book else null,
                    dirty = true, updatedAt = 100)
                val id = db.noteDao().upsert(note); expected[note.syncId] = note.content to if (note.notebookId == null) null else "book"
                if (i in special) {
                    db.tagDao().attachToNote(NoteTagCrossRef(id, tag))
                    val attachmentId = UUID.randomUUID().toString()
                    val file = File(files, "attachments/$attachmentId").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
                    db.attachmentDao().upsert(AttachmentEntity(noteId = id, syncId = attachmentId, filePath = file.path, mimeType = "image/png", sizeBytes = bytes.size.toLong()))
                }
            }
        }
        queries.clear(); val digests = AtomicInteger()
        val writers = Executors.newSingleThreadExecutor()
        val source = RoomSyncDataSource(context, db) { file ->
            assertFalse("Hash ran inside the SQL transaction", db.inTransaction())
            if (digests.getAndIncrement() == 0) {
                // A competing transaction must finish while hashing is paused. Checking only
                // inTransaction() on a different IO thread would miss a held Room transaction.
                writers.submit { db.runInTransaction(Runnable {
                    db.openHelper.writableDatabase.execSQL("UPDATE notes SET content = 'Concurrent', updatedAt = 200 WHERE syncId = 'note-0'")
                }) }.get(5, TimeUnit.SECONDS)
            }
            file.inputStream().use { Hashing.sha256Hex(it, SyncLimits.ATTACHMENT_BYTES) }
        }
        val result = try { source.getChangesSince(10_000) } finally {
            writers.shutdownNow(); assertTrue(writers.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals("Concurrent", db.noteDao().getBySyncId("note-0")!!.content)
        assertTrue(result.notebooks.isEmpty()); assertEquals(count, result.notes.size); assertEquals(special.size, digests.get())
        for (note in result.notes) {
            val (body, book) = expected.getValue(note.syncId); assertEquals(body, note.content); assertEquals(book, note.notebookSyncId)
            if (note.syncId.removePrefix("note-").toInt() in special) {
                assertEquals(listOf("Tag"), note.tagNames); assertEquals(hash, note.attachments.single().sha256)
            } else { assertTrue(note.tagNames.isEmpty()); assertTrue(note.attachments.isEmpty()) }
        }
        val selects = queries.filter { it.trimStart().startsWith("SELECT", true) && !it.contains("room_", true) }
        assertTrue("Per-note SQL queries: ${selects.size}", selects.size <= 12)
    }
    @Test fun `older metadata never requests replacement bytes for a newer local attachment`() = runBlocking {
        val note = NoteEntity(title = "Newer", updatedAt = 200, dirty = false).let { it.copy(id = db.noteDao().upsert(it)) }
        val current = byteArrayOf(9, 9, 9); val stale = byteArrayOf(1)
        val meta = meta(note, current)
        val target = File(files, "attachments/${meta.syncId}").apply { parentFile!!.mkdirs(); writeBytes(current) }
        db.attachmentDao().upsert(AttachmentEntity(noteId = note.id, syncId = meta.syncId, filePath = target.path,
            mimeType = meta.mimeType, sizeBytes = current.size.toLong()))
        val source = RoomSyncDataSource(context, db)
        val latest = source.getChangesSince(Long.MIN_VALUE).notes.single()
        val incoming = latest.copy(title = "Older", updatedAt = 100,
            attachments = listOf(meta.copy(sizeBytes = stale.size.toLong(), sha256 = Hashing.sha256Hex(stale))))
        val result = source.applyChanges(com.notessync.app.sync.protocol.ChangeSet(notes = listOf(incoming)))
        for (missing in result.missingAttachments) source.receiveAttachment(missing).use { sink -> sink.output.write(stale); sink.commit() }
        assertEquals("Newer", db.noteDao().getById(note.id)!!.title)
        assertArrayEquals("Transfer must preserve the newer bytes", current, target.readBytes())
        assertTrue("Ignored older note must not schedule overwriting its newer file", result.missingAttachments.isEmpty())
        assertArrayEquals(current, target.readBytes())
    }

}
