package com.notessync.app.data.quotes

import androidx.room.withTransaction
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuoteLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var library: QuoteLibrary
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); db = AppDatabase.get(app); library = QuoteLibrary(db) }
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `CRUD and single pin preserve identities update displayed text and clear deleted selection`() = runBlocking {
        val one = library.save(null, QuoteDraft("First", "Author")); val two = library.save(null, QuoteDraft("Second"))
        library.pin(db.customQuoteDao().get(one)!!); assertEquals(one, db.customQuoteDao().pinned()!!.id)
        library.save(db.customQuoteDao().get(one), QuoteDraft("Edited", "Author")); assertEquals("Edited", db.customQuoteDao().pinned()!!.text)
        library.pin(db.customQuoteDao().get(two)!!); assertEquals(two, db.customQuoteDao().pinned()!!.id)
        library.delete(db.customQuoteDao().get(two)!!); assertNull(db.customQuoteDao().pinned()); assertNotNull(db.customQuoteDao().get(one))
    }
    @Test fun `duplicate and stale edits deletes and pins cannot overwrite another version`() = runBlocking {
        val id = library.save(null, QuoteDraft("Quote", "Author")); val old = db.customQuoteDao().get(id)!!
        val duplicate = runCatching { library.save(null, QuoteDraft("QUOTE", "author", "Another source")) }.exceptionOrNull() as QuoteException
        assertEquals(QuoteProblem.DUPLICATE, duplicate.problem)
        library.save(old, QuoteDraft("New text", "Author"))
        for (action in listOf<suspend () -> Any?>({ library.save(old, QuoteDraft("stale")) }, { library.delete(old) }, { library.pin(old) })) {
            assertEquals(QuoteProblem.STALE, (runCatching { action() }.exceptionOrNull() as QuoteException).problem)
        }
        assertEquals("New text", db.customQuoteDao().get(id)!!.text)
    }
    @Test fun `preview never writes and commit rechecks duplicates added since preview`() = runBlocking {
        val entries = listOf(QuoteDraft("One", "A"), QuoteDraft("ONE", "a"), QuoteDraft("Two"))
        val preview = library.preview(entries); assertEquals(2, preview.newCount); assertEquals(1, preview.duplicates); assertEquals(0, db.customQuoteDao().count())
        library.save(null, QuoteDraft("Two"))
        assertEquals(QuoteImportResult(1, 2), library.import(preview)); assertEquals(2, db.customQuoteDao().count())
        assertEquals(0, library.preview(entries).newCount)
    }
    @Test fun `failure halfway through import rolls back all added rows and preserves current pin`() = runBlocking {
        val old = library.save(null, QuoteDraft("Keep")); library.pin(db.customQuoteDao().get(old)!!)
        val preview = library.preview(listOf(QuoteDraft("First"), QuoteDraft("FAIL")))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON custom_quotes WHEN NEW.text = 'FAIL' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { library.import(preview) }.isFailure)
        assertEquals(1, db.customQuoteDao().count()); assertEquals(old, db.customQuoteDao().pinned()!!.id)
    }
    @Test fun `actual version17 schema migrates to current without changing notes and with an empty quote library`() = runBlocking {
        val note = NoteEntity(title = "Existing", content = "Keep all text", textFormat = "styles", dayKey = 3000L)
        val id = db.noteDao().upsert(note); val before = db.noteDao().getById(id)
        val values = android.content.ContentValues()
        db.openHelper.readableDatabase.query("SELECT * FROM notes WHERE id = ?", arrayOf(id)).use { cursor ->
            assertTrue(cursor.moveToFirst()); android.database.DatabaseUtils.cursorRowToContentValues(cursor, values)
        }
        AppDatabase.closeInstance(); assertTrue(app.deleteDatabase("notessync.db"))
        val schemaPath = "schemas/com.notessync.app.data.local.AppDatabase/17.json"
        val schema = JSONObject(listOf(File(schemaPath), File("app", schemaPath)).first { it.isFile }.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("notessync.db"), null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.insertOrThrow("notes", null, values)
            old.version = 17
        }
        db = AppDatabase.get(app)
        assertEquals(before, db.noteDao().getById(id)); assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
        assertEquals(0, db.customQuoteDao().count()); assertNull(db.customQuoteDao().pinned())
    }
    @Test fun `capacity changed after preview rolls back the whole import and preserves current selection`() = runBlocking {
        val keep = library.save(null, QuoteDraft("Keep")); library.pin(db.customQuoteDao().get(keep)!!)
        db.withTransaction {
            repeat(1997) { i -> db.openHelper.writableDatabase.execSQL("INSERT INTO custom_quotes(text,author,source,fingerprint) VALUES(?,?,?,?)", arrayOf("Seed $i", "", "", "seed-$i")) }
        }
        val preview = library.preview(listOf(QuoteDraft("First"), QuoteDraft("Second")))
        library.save(null, QuoteDraft("Concurrent"))
        assertEquals(QuoteProblem.LIMIT, (runCatching { library.import(preview) }.exceptionOrNull() as QuoteException).problem)
        assertEquals(1999, db.customQuoteDao().count()); assertNull(db.customQuoteDao().duplicate(QuoteDraft("First").fingerprint()))
        assertEquals(keep, db.customQuoteDao().pinned()!!.id)
    }
}
