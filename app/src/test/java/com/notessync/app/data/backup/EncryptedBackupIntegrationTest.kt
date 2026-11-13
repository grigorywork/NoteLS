package com.notessync.app.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import com.notessync.app.R
import com.notessync.app.data.backup.crypto.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EncryptedBackupIntegrationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    private val db get() = AppDatabase.get(app)
    private val password get() = "integration password пароль".toCharArray()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        app.getSharedPreferences(BackupProtection.PREFS, 0).edit().clear().commit()
        File(app.filesDir, "backups").deleteRecursively()
        app.getExternalFilesDir(null)?.let { File(it, "backups").deleteRecursively() }
        settings.setBackupFrequency(BackupFrequency.MANUAL); settings.setLastAutoBackupAt(0)
    }
    @After fun after() { AppDatabase.closeInstance() }
    private suspend fun setup() = BackupManager.configureProtection(app, null, password)
    private suspend fun archive(): File = File(app.cacheDir, "encrypted.nlsbackup").also { file ->
        file.outputStream().use { BackupManager.createBackup(app, it) }
    }
    private suspend fun restore(file: File) = BackupManager.unlock(app, Uri.fromFile(file), password).use {
        BackupManager.restore(app, Uri.fromFile(file), it)
    }
    @Test fun `unconfigured manual and automatic backups never write plaintext`() = runBlocking {
        db.noteDao().upsert(NoteEntity(title = "Must stay private"))
        val output = ByteArrayOutputStream()
        assertTrue(runCatching { BackupManager.createBackup(app, output) }.isFailure); assertEquals(0, output.size())
        settings.setBackupFrequency(BackupFrequency.DAILY)
        BackupManager.autoBackupIfDue(app, false)
        assertTrue(BackupManager.localBackups(app).isEmpty()); assertEquals(0L, settings.state.value.lastAutoBackupAt)
    }
    @Test fun `manual archive is encrypted and restores after local configuration is gone`() = runBlocking {
        setup(); val id = db.noteDao().upsert(NoteEntity(title = "private_unique_123", content = "body123"))
        val file = archive(); val bytes = file.readBytes()
        assertArrayEquals(BackupEnvelopeCodec.MAGIC, bytes.copyOf(8))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("private_unique_123"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("SQLite format 3"))
        assertTrue(BackupManager.inspect(app, Uri.fromFile(file))!!.encrypted)
        app.getSharedPreferences(BackupProtection.PREFS, 0).edit().clear().commit()
        db.noteDao().update(db.noteDao().getById(id)!!.copy(title = "Changed"))
        assertTrue(restore(file).isSuccess); assertEquals("private_unique_123", db.noteDao().getById(id)!!.title)
        assertNull(BackupManager.identity(app)) // Import never silently changes auto-backup recipient.
    }
    @Test fun `wrong password and late authentication failure leave current live data unchanged`() = runBlocking {
        setup(); val id = db.noteDao().upsert(NoteEntity(title = "Archived")); val file = archive()
        db.noteDao().update(db.noteDao().getById(id)!!.copy(title = "Current"))
        assertTrue(runCatching { BackupManager.unlock(app, Uri.fromFile(file), "wrong password".toCharArray()) }.isFailure)
        assertEquals("Current", db.noteDao().getById(id)!!.title)
        val valid = file.readBytes()
        for (bad in listOf(valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, valid.dropLast(25).toByteArray(), valid + byteArrayOf(9))) {
            file.writeBytes(bad); assertTrue(restore(file).isFailure)
            assertEquals("Current", db.noteDao().getById(id)!!.title)
            assertFalse(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("restore-") })
        }
    }
    @Test fun `automatic copy and exported legacy copy are encrypted and local conversion preserves restore`() = runBlocking {
        setup(); val id = db.noteDao().upsert(NoteEntity(title = "Automatic"))
        settings.setBackupFrequency(BackupFrequency.DAILY); BackupManager.autoBackupIfDue(app, false)
        val auto = BackupManager.localBackups(app).single(); assertTrue(auto.info.encrypted)
        assertEquals("nlsbackup", auto.file.extension); assertTrue(settings.state.value.lastAutoBackupAt > 0)
        val rawZip = BackupManager.unlock(app, Uri.fromFile(auto.file), password).use { lease ->
            BackupEncryptedIO.read(auto.file.inputStream(), lease) { it.readBytes() }
        }
        val legacy = File(auto.file.parentFile, "notels-auto-old.zip").apply { writeBytes(rawZip); setLastModified(1234567) }
        val output = ByteArrayOutputStream(); BackupManager.exportCopy(app, legacy, output)
        assertArrayEquals(BackupEnvelopeCodec.MAGIC, output.toByteArray().copyOf(8))
        assertArrayEquals(rawZip, legacy.readBytes()) // Export does not destroy the local source.
        assertEquals(1, BackupManager.protectLocalCopies(app)); assertEquals(1234567L, legacy.lastModified())
        assertTrue(BackupManager.inspect(app, Uri.fromFile(legacy))!!.encrypted)
        db.noteDao().update(db.noteDao().getById(id)!!.copy(title = "Changed"))
        assertTrue(restore(legacy).isSuccess); assertEquals("Automatic", db.noteDao().getById(id)!!.title)
        assertEquals(0, BackupManager.protectLocalCopies(app))
        val raw = File(app.cacheDir, "legacy.zip").apply { writeBytes(rawZip) }
        assertFalse(BackupManager.inspect(app, Uri.fromFile(raw))!!.encrypted)
        assertTrue(BackupManager.restore(app, Uri.fromFile(raw)).isSuccess)
    }
    @Test fun `failed old copy conversion retains original and removes pending file`() = runBlocking {
        setup(); db.noteDao().upsert(NoteEntity(title = "Legacy"))
        settings.setBackupFrequency(BackupFrequency.DAILY); BackupManager.autoBackupIfDue(app, false)
        val auto = BackupManager.localBackups(app).single()
        val zip = BackupManager.unlock(app, Uri.fromFile(auto.file), password).use { lease -> BackupEncryptedIO.read(auto.file.inputStream(), lease) { it.readBytes() } }
        val old = File(auto.file.parentFile, "notels-auto-old.zip").apply { writeBytes(zip) }
        val pending = File(old.parentFile, old.name + ".protecting").apply { mkdir() }
        assertTrue(runCatching { BackupManager.protectLocalCopies(app) }.isFailure)
        assertArrayEquals(zip, old.readBytes()); assertFalse(pending.exists())
        assertTrue(BackupManager.restore(app, Uri.fromFile(old)).isSuccess)
    }
    @Test fun `OS backup and both transfer paths exclude plaintext and device protected data`() {
        val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val exclusions = mutableMapOf<String, MutableSet<String>>()
            var section = "legacy"
            app.resources.getXml(resource).use { parser ->
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        if (parser.name == "cloud-backup" || parser.name == "device-transfer") section = parser.name
                        if (parser.name == "exclude" && parser.getAttributeValue(null, "path") == ".") {
                            exclusions.getOrPut(section) { mutableSetOf() }.add(parser.getAttributeValue(null, "domain"))
                        }
                    }
                    parser.next()
                }
            }
            assertEquals(if (resource == R.xml.backup_rules) setOf("legacy") else setOf("cloud-backup", "device-transfer"), exclusions.keys)
            exclusions.values.forEach { assertEquals(domains, it) }
        }
    }
    @Test fun `incorrect old password never replaces backup configuration`() = runBlocking {
        setup(); val original = BackupManager.identity(app)
        assertTrue(runCatching { BackupManager.configureProtection(app, "wrong password".toCharArray(), "new password123".toCharArray()) }.isFailure)
        assertEquals(original, BackupManager.identity(app)); original!!.unlock(password).close()
    }
}
