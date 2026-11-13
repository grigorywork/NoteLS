package com.notessync.app.data.backup.audit

import android.app.Application
import android.content.Context
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.backup.crypto.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.SyncWorkGate
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinated synthetic transfer uses the real shared gate and Room file publisher, with no network/phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupConcurrentAttachmentAuditProbe {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val password get() = "audit fixture password".toCharArray()
    @Before fun before() = runBlocking {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        app.getSharedPreferences(BackupProtection.PREFS, 0).edit().clear().commit()
        BackupProtection(app).save(BackupIdentity.create(password, 100000))
    }
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `concurrent valid peer publication cannot mix frozen DB metadata with different file bytes`() = runBlocking {
        val random = ByteArray(200000).also { Random(42).nextBytes(it) }
        val note = NoteEntity(title = "Synthetic snapshot", content = Base64.getEncoder().encodeToString(random), dirty = false)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val file = File(app.filesDir, "attachments/11111111-1111-1111-1111-111111111111").apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(10) { 1 })
        }
        val row = AttachmentEntity(syncId = file.name, noteId = note.id, filePath = file.path,
            mimeType = "image/jpeg", sizeBytes = 10)
        db.attachmentDao().upsert(row)
        val source = RoomSyncDataSource(app, db)
        val original = source.getChangesSince(Long.MIN_VALUE)
        val newBytes = ByteArray(20) { 2 }
        val oldDto = original.notes.single().attachments.single()
        val newMeta = oldDto.copy(sizeBytes = 20, sha256 = MessageDigest.getInstance("SHA-256").digest(newBytes).joinToString("") { "%02x".format(it) })
        val changed = original.copy(notes = original.notes.map { it.copy(updatedAt = it.updatedAt + 1000, attachments = listOf(newMeta)) })
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val gated = AtomicBoolean()
        val bytes = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == EncryptedBackupStreams.CHUNK + 16 && gated.compareAndSet(false, true)) {
                    entered.complete(Unit)
                    check(release.await(15, TimeUnit.SECONDS)) { "Audit coordination timed out" }
                }
                bytes.write(b, off, len)
            }
        }
        val backup = async(Dispatchers.IO) { BackupManager.createBackup(app, output) }
        try {
            withTimeout(15000) { entered.await() } // First encrypted DB block, after the snapshot/preflight, before the attachment opens.
            val exchange = async(Dispatchers.IO) { SyncWorkGate.transfer(automatic = false) {
                source.applyChanges(changed); source.writeAttachmentBytes(newMeta, newBytes)
            } }
            // A correctly shared gate may defer the peer until backup finishes. This timeout only coordinates tasks; it is not a performance target.
            withTimeoutOrNull(3000) { exchange.await() }
            release.countDown(); backup.await(); exchange.await()
            val packed = File(app.cacheDir, "concurrent.nlsbackup").apply { writeBytes(bytes.toByteArray()) }
            BackupManager.unlock(app, Uri.fromFile(packed), password).use {
                val restored = BackupManager.restore(app, Uri.fromFile(packed), it)
                assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
            }
            val restored = db.attachmentDao().getBySyncId(row.syncId)!!
            assertEquals("A successful backup must keep attachment metadata and file bytes coherent",
                restored.sizeBytes, File(restored.filePath).length())
        } finally { release.countDown() }
    }
}
