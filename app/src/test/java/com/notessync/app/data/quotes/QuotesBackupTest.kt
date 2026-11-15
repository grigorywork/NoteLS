package com.notessync.app.data.quotes

import android.app.Application
import android.net.Uri
import com.notessync.app.data.backup.EncryptedBackupFixture
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuotesBackupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var library: QuoteLibrary
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); db = AppDatabase.get(app); library = QuoteLibrary(db) }
    @After fun after() { AppDatabase.closeInstance() }
    private suspend fun backup(): File {
        val output = ByteArrayOutputStream(); EncryptedBackupFixture.createBackup(app, output)
        return File(app.cacheDir, "quotes.backup").apply { writeBytes(output.toByteArray()) }
    }
    @Test fun `encrypted backup restores the entire quote library selection and notes in the existing Room instance`() = runBlocking {
        val one = library.save(null, QuoteDraft("Сохранить 😀", "Автор", "Книга")); val original = db.customQuoteDao().get(one)!!
        library.pin(original); val note = db.noteDao().upsert(NoteEntity(title = "Before"))
        val file = backup(); library.save(original, QuoteDraft("Changed")); library.save(null, QuoteDraft("Extra")); library.clearPin()
        db.noteDao().update(db.noteDao().getById(note)!!.copy(title = "After"))
        val restored = EncryptedBackupFixture.restore(app, Uri.fromFile(file))
        assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
        assertEquals(1, db.customQuoteDao().count()); assertEquals(original, db.customQuoteDao().pinned()); assertEquals("Before", db.noteDao().getById(note)!!.title)
        AppDatabase.closeInstance(); db = AppDatabase.get(app); assertEquals(original, db.customQuoteDao().pinned())
    }
    @Test fun `quote insertion failure rolls back restored notes and preserves current library and pin`() = runBlocking {
        val id = library.save(null, QuoteDraft("Archive")); library.pin(db.customQuoteDao().get(id)!!)
        val note = db.noteDao().upsert(NoteEntity(title = "Archive note")); val file = backup()
        library.save(db.customQuoteDao().get(id), QuoteDraft("Current")); val current = db.customQuoteDao().get(id)
        db.noteDao().update(db.noteDao().getById(note)!!.copy(title = "Current note"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore_quotes BEFORE INSERT ON custom_quotes BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(EncryptedBackupFixture.restore(app, Uri.fromFile(file)).isFailure)
        assertEquals(current, db.customQuoteDao().pinned()); assertEquals("Current note", db.noteDao().getById(note)!!.title)
    }
    @Test fun `invalid quote metadata in an archive is rejected before any live rows change`() = runBlocking {
        val id = library.save(null, QuoteDraft("Keep")); library.pin(db.customQuoteDao().get(id)!!)
        db.openHelper.writableDatabase.execSQL("INSERT INTO custom_quotes(text,author,source,fingerprint) VALUES('Invalid','','','wrong-identity')")
        val file = backup()
        db.openHelper.writableDatabase.execSQL("DELETE FROM custom_quotes WHERE fingerprint = 'wrong-identity'")
        assertTrue(EncryptedBackupFixture.restore(app, Uri.fromFile(file)).isFailure)
        assertEquals(1, db.customQuoteDao().count()); assertEquals(id, db.customQuoteDao().pinned()!!.id)
    }
}
