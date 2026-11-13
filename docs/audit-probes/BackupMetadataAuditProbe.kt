package com.notessync.app.data.backup.audit

import android.app.Application
import android.content.Context
import android.net.Uri
import com.notessync.app.data.backup.BackupArchive
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.backup.crypto.BackupIdentity
import com.notessync.app.data.backup.crypto.BackupProtection
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Temporary audit reproduction: filesystem copy time must not replace the archive's creation time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupMetadataAuditProbe {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val createdAt = 1_500_000_000_000L
    private val copiedAt = 1_700_000_000_000L
    @Before fun before() = runBlocking {
        app.getSharedPreferences(BackupProtection.PREFS, 0).edit().clear().commit()
        File(app.filesDir, "backups").deleteRecursively()
        app.getExternalFilesDir(null)?.let { File(it, "backups").deleteRecursively() }
        BackupProtection(app).save(BackupIdentity.create("audit fixture password".toCharArray(), 100000))
    }
    private fun legacy(): File = File(app.filesDir, "backups/notels-auto-old.zip").apply {
        parentFile!!.mkdirs()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write(BackupArchive.manifest(BackupArchive.Info(createdAt, 0, 17)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE)); zip.write("SQLite format 3\u0000".toByteArray()); zip.closeEntry()
        }
        check(setLastModified(copiedAt))
    }
    @Test fun `exporting copied legacy ZIP preserves manifest date`() = runBlocking {
        val original = legacy()
        assertEquals(createdAt, BackupManager.inspect(app, Uri.fromFile(original))!!.createdAt)
        val bytes = ByteArrayOutputStream(); BackupManager.exportCopy(app, original, bytes)
        val exported = File(app.cacheDir, "exported.nlsbackup").apply { writeBytes(bytes.toByteArray()) }
        assertEquals(createdAt, BackupManager.inspect(app, Uri.fromFile(exported))!!.createdAt)
    }
    @Test fun `converting copied legacy ZIP preserves manifest date and filesystem timestamp independently`() = runBlocking {
        val original = legacy()
        assertEquals(1, BackupManager.protectLocalCopies(app))
        assertEquals(copiedAt, original.lastModified())
        assertEquals(createdAt, BackupManager.inspect(app, Uri.fromFile(original))!!.createdAt)
    }
}
