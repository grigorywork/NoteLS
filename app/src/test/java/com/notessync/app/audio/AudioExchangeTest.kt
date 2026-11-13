package com.notessync.app.audio

import com.notessync.app.data.backup.EncryptedBackupFixture
import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.protocol.SyncMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AudioExchangeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db") }
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `audio metadata and exact binary fixture survive backup restore and peer transfer then purge`() = runBlocking {
        val db = AppDatabase.get(app); val attachmentId = UUID.randomUUID().toString()
        // Arbitrary bytes prove transport/file identity, not audio encoding or microphone quality.
        val bytes = ByteArray(70_001) { (it % 251).toByte() }
        val original = File(app.filesDir, "attachments/$attachmentId").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val note = NoteEntity(title = "Аудиозапись", content = "Мой текст").let { it.copy(id = db.noteDao().upsert(it)) }
        db.attachmentDao().upsert(AttachmentEntity(syncId = attachmentId, noteId = note.id, filePath = original.path,
            mimeType = "audio/mp4", sizeBytes = bytes.size.toLong()))
        val archive = ByteArrayOutputStream(); EncryptedBackupFixture.createBackup(app, archive)
        val packed = File(app.cacheDir, "audio.zip").apply { writeBytes(archive.toByteArray()) }
        original.delete(); assertTrue(EncryptedBackupFixture.restore(app, Uri.fromFile(packed)).isSuccess)
        val restored = db.attachmentDao().getBySyncId(attachmentId)!!
        assertEquals("audio/mp4", restored.mimeType); assertArrayEquals(bytes, File(restored.filePath).readBytes())
        assertEquals(note, db.noteDao().getById(note.id)); assertTrue(db.attachmentDao().observeFirstPhotos().first().isEmpty())
        val source = RoomSyncDataSource(app, db)
        val message = SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(source.getChangesSince(0)))) as SyncMessage.Changes
        val dto = message.set.notes.single(); val meta = dto.attachments.single(); assertEquals("audio/mp4", meta.mimeType)
        val peerFiles = File(app.cacheDir, "audio-peer").apply { mkdirs() }
        val peerContext = object : ContextWrapper(app) {
            override fun getFilesDir() = peerFiles
            override fun getApplicationContext() = this
        }
        val peerDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val peer = RoomSyncDataSource(peerContext, peerDb)
            peer.applyChanges(message.set); peer.writeAttachmentBytes(meta, source.readAttachmentBytes(attachmentId)!!)
            val peerNote = peerDb.noteDao().getBySyncId(dto.syncId)!!; val received = peerDb.attachmentDao().getByNoteId(peerNote.id).single()
            assertEquals("audio/mp4", received.mimeType); assertArrayEquals(bytes, File(received.filePath).readBytes())
            val actions = NoteActions(peerContext, peerDb)
            actions.apply(setOf(peerNote.id), NoteActions.Action.TRASH); actions.apply(setOf(peerNote.id), NoteActions.Action.PURGE)
            assertFalse(File(received.filePath).exists())
            peer.applyChanges(message.set); peer.writeAttachmentBytes(meta, bytes)
            assertTrue(peerDb.noteDao().getById(peerNote.id)!!.isPurged); assertTrue(peerDb.attachmentDao().getByNoteId(peerNote.id).isEmpty())
            assertFalse(File(received.filePath).exists())
        } finally { peerDb.close() }
    }
}
