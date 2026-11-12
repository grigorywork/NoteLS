package com.notessync.app.notes

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.text.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
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
class FormattingPersistenceTest {
    @Before fun setUp() { AppDatabase.closeInstance(); RuntimeEnvironment.getApplication().deleteDatabase("notessync.db") }
    @After fun tearDown() { AppDatabase.closeInstance() }

    @Test fun `database ten migrates real data without adding a priority or formatting`() = runBlocking {
        val relative = "schemas/com.notessync.app.data.local.AppDatabase/10.json"
        val schema = JSONObject(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val context = RuntimeEnvironment.getApplication(); val path = context.getDatabasePath("notessync.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(notes)", null).use { columns ->
                while (columns.moveToNext()) if (columns.getInt(3) != 0 || columns.getInt(5) != 0) {
                    val name = columns.getString(1)
                    if (columns.getString(2) == "TEXT") values.put(name, when (name) { "title" -> "Before"; "syncId" -> "legacy"; else -> "" })
                    else values.put(name, if (name == "id" || name == "isTask") 1L else 0L)
                }
            }
            old.insertOrThrow("notes", null, values); old.version = 10
        }
        val note = AppDatabase.get(context).noteDao().getById(1)!!
        assertEquals("Before", note.title); assertEquals(0, note.taskPriority); assertEquals("", note.textFormat)
        assertEquals(AppDatabase.VERSION, AppDatabase.get(context).openHelper.readableDatabase.version)
    }
    @Test fun `priority and formatting survive backup restore and direct peer exchange`() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val db = AppDatabase.get(context)
        val fmt = TextFormatting().apply(0, 6, FormatKind.SIZE, "24").apply(0, 6, FormatKind.FONT, "MONO")
            .apply(0, 6, FormatKind.ALIGN, "CENTER").apply(0, 6, FormatKind.INDENT, "1").apply(2, 6, FormatKind.HIGHLIGHT, "FFFFD54F").encode()
        val id = db.noteDao().upsert(NoteEntity(title = "Style", content = "Привет", isTask = true, taskPriority = 3, textFormat = fmt))
        val packed = ByteArrayOutputStream(); BackupManager.createBackup(context, packed)
        val file = File(context.cacheDir, "format-test.zip").apply { writeBytes(packed.toByteArray()) }
        db.noteDao().update(db.noteDao().getById(id)!!.copy(content = "Changed", taskPriority = 0, textFormat = ""))
        assertTrue(BackupManager.restore(context, Uri.fromFile(file)).isSuccess)
        val restored = db.noteDao().getById(id)!!
        assertEquals("Привет", restored.content); assertEquals(3, restored.taskPriority); assertEquals(fmt, restored.textFormat)
        val sync = RoomSyncDataSource(context, db); val dto = sync.getChangesSince(0).notes.single()
        assertEquals(fmt, dto.textFormat); assertEquals(3, dto.taskPriority)
        sync.applyChanges(ChangeSet(notes = listOf(dto.copy(syncId = "peer", taskPriority = 2))))
        assertEquals(fmt, db.noteDao().getBySyncId("peer")!!.textFormat); assertEquals(2, db.noteDao().getBySyncId("peer")!!.taskPriority)
    }
    @Test fun `old protocol messages and conflicts keep compatible defaults and local formatting`() {
        val raw = """{"syncId":"one","title":"t","content":"body","favorite":false,"deleted":false,"createdAt":1,"updatedAt":1}"""
        val plain = Json.decodeFromString<NoteDto>(raw)
        assertEquals("", plain.textFormat); assertEquals(0, plain.taskPriority)
        val local = plain.copy(taskPriority = 3, textFormat = TextFormatting().apply(0, 4, FormatKind.BOLD, "1").encode())
        assertEquals(local, Json.decodeFromString<NoteDto>(Json.encodeToString(local)))
        val conflict = ConflictResolver.resolve(local, plain.copy(updatedAt = 2), true) as ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy
        assertEquals(local.textFormat, conflict.conflictCopy.textFormat); assertEquals(3, conflict.conflictCopy.taskPriority)
    }
}
