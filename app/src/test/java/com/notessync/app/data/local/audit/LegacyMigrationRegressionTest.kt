package com.notessync.app.data.local.audit

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.backup.BackupArchive
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.notessync.app.data.local.AppDatabase
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

/** Uses the version1 schema tracked in this repository, not an archived source tree or user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyMigrationRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db") }
    @After fun after() { AppDatabase.closeInstance() }
    private fun createVersion1(fileName: String = "notessync.db"): File {
        val name = "schemas/com.notessync.app.data.local.AppDatabase/1.json"
        val schema = JSONObject(listOf(File(name), File("app", name)).first { it.isFile }.readText()).getJSONObject("database")
        val path = app.getDatabasePath(fileName); path.parentFile!!.mkdirs()
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
            old.execSQL("INSERT INTO notebooks(id,name,createdAt) VALUES(1,'Legacy book',1000)")
            old.execSQL("INSERT INTO notes(id,title,content,notebookId,isFavorite,isDeleted,createdAt,updatedAt,dirty) VALUES(1,'Legacy one','Preserved body',1,1,0,1000,2000,0)")
            old.execSQL("INSERT INTO notes(id,title,content,notebookId,isFavorite,isDeleted,createdAt,updatedAt,dirty) VALUES(2,'Legacy two','Second body',1,0,0,1000,2000,0)")
            old.execSQL("INSERT INTO tags(id,name) VALUES(1,'legacy tag')")
            old.execSQL("INSERT INTO note_tag_cross_ref(noteId,tagId) VALUES(1,1)")
            old.execSQL("INSERT INTO attachments(id,noteId,filePath,mimeType,sizeBytes,createdAt) VALUES(1,1,'/fixture/attachments/old','image/jpeg',3,1000)")
            old.version = 1
        }
        return path
    }
    @Test fun `real version1 database migrates with all relations and stable distinct identities`() = runBlocking {
        createVersion1()
        var db = AppDatabase.get(app)
        val one = db.noteDao().getById(1)!!; val two = db.noteDao().getById(2)!!
        assertEquals("Preserved body", one.content); assertEquals(1L, one.notebookId)
        assertTrue(one.syncId.isNotBlank()); assertTrue(two.syncId.isNotBlank()); assertNotEquals(one.syncId,two.syncId)
        assertTrue(one.dirty); assertTrue(one.updatedAt > 2000)
        assertEquals(listOf("legacy tag"), db.tagDao().getTagNamesForNote(1))
        assertEquals(1, db.attachmentDao().getByNoteId(1).size)
        assertTrue(db.notebookDao().getById(1)!!.syncId.isNotBlank())
        AppDatabase.closeInstance(); db = AppDatabase.get(app)
        assertEquals(one.syncId, db.noteDao().getById(1)!!.syncId)
        assertEquals(two.syncId, db.noteDao().getById(2)!!.syncId)
    }
    @Test fun `version1 legacy archive restores through migrated staging without losing relations`() = runBlocking {
        val source = createVersion1("legacy-source.db")
        val archive = File(app.cacheDir, "legacy-version1.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write(BackupArchive.manifest(BackupArchive.Info(1000, 2, 1)).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE)); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("attachments/old")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
        }
        val result = BackupManager.restore(app, Uri.fromFile(archive))
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        var db = AppDatabase.get(app)
        val restored = db.noteDao().getById(1)!!
        assertEquals("Preserved body", restored.content); assertTrue(restored.syncId.isNotBlank())
        assertNotEquals(restored.syncId, db.noteDao().getById(2)!!.syncId)
        assertEquals(listOf("legacy tag"), db.tagDao().getTagNamesForNote(1))
        val photo = db.attachmentDao().getByNoteId(1).single()
        assertTrue(photo.filePath.startsWith(app.filesDir.path))
        assertArrayEquals(byteArrayOf(1,2,3), File(photo.filePath).readBytes())
        AppDatabase.closeInstance(); db = AppDatabase.get(app)
        assertEquals(restored.syncId, db.noteDao().getById(1)!!.syncId)
    }

}
