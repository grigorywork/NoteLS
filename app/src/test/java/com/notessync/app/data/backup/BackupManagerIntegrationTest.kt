package com.notessync.app.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs real Android SQLite / Room code without touching any user's device or notes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupManagerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        AppDatabase.closeInstance()
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("notessync.db")
        database = AppDatabase.get(context)
    }

    @After fun tearDown() { AppDatabase.closeInstance() }

    private suspend fun archive(): File {
        val output = ByteArrayOutputStream()
        BackupManager.createBackup(context, output)
        return File(context.cacheDir, "sample.zip").apply { writeBytes(output.toByteArray()) }
    }

    @Test fun `committed WAL notes and photos survive backup delete and restore`() = runBlocking {
        val id = database.noteDao().upsert(NoteEntity(title = "До копии", content = "Текст".repeat(4000)))
        val photo = File(context.filesDir, "attachments/photo").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(7, 8, 9))
        }
        database.attachmentDao().upsert(AttachmentEntity(noteId = id, filePath = photo.absolutePath,
            mimeType = "image/jpeg", sizeBytes = 3))
        val file = archive()
        database.noteDao().update(database.noteDao().getById(id)!!.copy(title = "После копии"))
        photo.delete()

        val result = BackupManager.restore(context, Uri.fromFile(file))
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        assertEquals("До копии", database.noteDao().getById(id)!!.title)
        database.openHelper.readableDatabase.query("SELECT filePath FROM attachments").use {
            assertTrue(it.moveToFirst())
            val restored = File(it.getString(0))
            assertNotEquals(photo.absolutePath, restored.absolutePath)
            assertArrayEquals(byteArrayOf(7, 8, 9), restored.readBytes())
        }
        // A second cycle must also work with rewritten attachment paths.
        val second = archive()
        assertTrue(BackupManager.restore(context, Uri.fromFile(second)).isSuccess)
    }

    @Test fun `failed insertion rolls back old notes and keeps original photograph`() = runBlocking {
        val id = database.noteDao().upsert(NoteEntity(title = "Из копии"))
        val file = archive()
        database.noteDao().update(database.noteDao().getById(id)!!.copy(title = "Текущая заметка"))
        val photo = File(context.filesDir, "attachments/current").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3))
        }
        database.attachmentDao().upsert(AttachmentEntity(noteId = id, filePath = photo.absolutePath,
            mimeType = "image/jpeg", sizeBytes = 3))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER simulate_restore_failure BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END"
        )

        assertTrue(BackupManager.restore(context, Uri.fromFile(file)).isFailure)
        assertEquals("Текущая заметка", database.noteDao().getById(id)!!.title)
        assertArrayEquals(byteArrayOf(1, 2, 3), photo.readBytes())
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM attachments").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun `a corrupt archive leaves all current data untouched`() = runBlocking {
        val id = database.noteDao().upsert(NoteEntity(title = "Не удалять"))
        val bad = File(context.cacheDir, "bad.zip").apply { writeText("not a zip") }
        assertTrue(BackupManager.restore(context, Uri.fromFile(bad)).isFailure)
        assertEquals("Не удалять", database.noteDao().getById(id)!!.title)
    }
}
