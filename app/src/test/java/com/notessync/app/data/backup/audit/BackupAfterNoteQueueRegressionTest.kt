package com.notessync.app.data.backup.audit

import android.app.Application
import android.content.Context
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.backup.crypto.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.*
import java.util.Base64
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Public event entrypoint: a note saved during another backup must not silently lose its scheduled copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupAfterNoteQueueRegressionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val settings get() = SettingsRepository.get(app)
    private val password get() = "audit fixture password".toCharArray()
    @Before fun before() = runBlocking {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        app.getSharedPreferences(BackupProtection.PREFS, 0).edit().clear().commit()
        File(app.filesDir, "backups").deleteRecursively()
        app.getExternalFilesDir(null)?.let { File(it, "backups").deleteRecursively() }
        BackupProtection(app).save(BackupIdentity.create(password, 100000))
        settings.setBackupFrequency(BackupFrequency.AFTER_NOTE); settings.setLastAutoBackupAt(0)
    }
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `after note request received while a backup is busy eventually covers that note`() = runBlocking {
        val noise = ByteArray(200000).also { Random(42).nextBytes(it) }
        db.noteDao().upsert(NoteEntity(title = "First", content = Base64.getEncoder().encodeToString(noise)))
        BackupManager.noteChanged()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val gated = AtomicBoolean()
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == EncryptedBackupStreams.CHUNK + 16 && gated.compareAndSet(false, true)) {
                    entered.complete(Unit); check(release.await(15, TimeUnit.SECONDS))
                }
            }
        }
        val existing = async(Dispatchers.IO) { BackupManager.createBackup(app, output) }
        try {
            withTimeout(15000) { entered.await() }
            val second = db.noteDao().upsert(NoteEntity(title = "Second saved while busy"))
            BackupManager.noteChanged(); BackupManager.requestAutoBackup(app, afterNote = true)
            delay(1000) // Allow the public asynchronous request to run while the first snapshot is deliberately held.
            release.countDown(); existing.await()
            withTimeout(15000) { settings.state.first { it.lastAutoBackupAt > 0 } }
            val copy = BackupManager.localBackups(app).first()
            db.noteDao().update(db.noteDao().getById(second)!!.copy(title = "After the automatic copy"))
            BackupManager.unlock(app, Uri.fromFile(copy.file), password).use {
                assertTrue(BackupManager.restore(app, Uri.fromFile(copy.file), it).isSuccess)
            }
            assertEquals("Second saved while busy", db.noteDao().getById(second)!!.title)
        } finally { release.countDown() }
    }
}
