package com.notessync.app.widget

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.dao.COMPACT_WIDGET_SQL
import com.notessync.app.data.local.entity.*
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.flow.first
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
class CompactWidgetQueryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll() }
    @After fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    @Test fun `three bounded public rows follow pins and dates while every protected or deleted record is excluded`() = runBlocking {
        val db = AppDatabase.get(app); val book = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true))
        ProtectionSession.unlockNotebook(book)
        val pin = db.noteDao().upsert(NoteEntity(title = "Pinned", isPinned = true, updatedAt = 1))
        val text = "😀".repeat(600)
        val second = db.noteDao().upsert(NoteEntity(title = text, content = "body".repeat(100_000), updatedAt = 100))
        val third = db.noteDao().upsert(NoteEntity(title = "Later tie", updatedAt = 100, isTask = true))
        db.noteDao().upsert(NoteEntity(title = "Fourth", updatedAt = 99))
        db.noteDao().upsert(NoteEntity(title = "Private note", isProtected = true, updatedAt = 10_000))
        db.noteDao().upsert(NoteEntity(title = "Private parent", notebookId = book, updatedAt = 10_001))
        db.noteDao().upsert(NoteEntity(title = "Trash", isDeleted = true, updatedAt = 10_002))
        db.noteDao().upsert(NoteEntity(title = "Purged", isPurged = true, updatedAt = 10_003))
        val rows = db.noteDao().observeCompactWidget().first()
        assertEquals(listOf(pin, third, second), rows.map { it.id })
        assertEquals(512, rows.last().title.length); assertFalse(rows.last().title.last().isHighSurrogate())
        assertEquals(256, rows.last().content.length); assertEquals(text, db.noteDao().getById(second)!!.title)
        assertEquals(400_000, db.noteDao().getById(second)!!.content.length)
        db.notebookDao().update(db.notebookDao().getById(book)!!.copy(isDeleted = true))
        assertEquals(listOf(pin, third, second), db.noteDao().observeCompactWidget().first().map { it.id })
    }
    @Test fun `schema fifteen upgrades preserving data and the ordering index and the real query uses covering metadata`() = runBlocking {
        val relative = "schemas/com.notessync.app.data.local.AppDatabase/15.json"
        val schema = JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val path = app.getDatabasePath("notessync.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(notes)", null).use { columns -> while (columns.moveToNext()) {
                if (columns.getInt(3) != 0 || columns.getInt(5) != 0) {
                    val name = columns.getString(1)
                    if (columns.getString(2) == "TEXT") values.put(name, when (name) {
                        "syncId" -> "old-note"; "title" -> "До миграции"; "content" -> "Текст 😀"; else -> ""
                    }) else values.put(name, if (name == "id") 1L else 0L)
                }
            } }
            values.put("isPinned", 1); old.insertOrThrow("notes", null, values); old.version = 15
        }
        val db = AppDatabase.get(app); val note = db.noteDao().getById(1)!!
        assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version); assertEquals("Текст 😀", note.content); assertTrue(note.isPinned)
        val indexes = db.openHelper.readableDatabase.query("PRAGMA index_list(notes)").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(1)) }
        }
        assertTrue("index_notes_visible_order" in indexes); assertFalse("index_notes_isDeleted_isPurged_isPinned_updatedAt" in indexes)
        val plan = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $COMPACT_WIDGET_SQL").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(3)) }
        }
        assertTrue(plan.toString(), plan.any { it.contains("COVERING INDEX index_notes_visible_order") })
        assertEquals("До миграции", db.noteDao().observeCompactWidget().first().single().title)
    }
    @Test fun `widget links recheck identity and refuse private or purged records even if unlocked`() = runBlocking {
        val db = AppDatabase.get(app); val note = NoteEntity(title = "Public").let { it.copy(id = db.noteDao().upsert(it)) }
        val intent = WidgetNoteLinks.intent(app, 1, note.id, note.syncId)
        val request = WidgetNoteLinks.read(intent, 1)!!; assertTrue(WidgetNoteLinks.canOpen(app, request))
        db.noteDao().update(note.copy(isProtected = true)); ProtectionSession.unlockTask(note.syncId)
        assertFalse(WidgetNoteLinks.canOpen(app, request))
        db.noteDao().update(note.copy(isPurged = true)); assertFalse(WidgetNoteLinks.canOpen(app, request))
        db.noteDao().update(note.copy(syncId = "replacement")); assertFalse(WidgetNoteLinks.canOpen(app, request))
        assertNull(WidgetNoteLinks.read(WidgetNoteLinks.intent(app, 1, -1, note.syncId), 2))
        assertNull(WidgetNoteLinks.read(WidgetNoteLinks.intent(app, 1, note.id, "x".repeat(161)), 3))
    }
}
