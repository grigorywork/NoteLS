package com.notessync.app.notes.actions

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.security.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.protocol.*
import com.notessync.app.ui.notes.*
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
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NoteActionsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val actions get() = NoteActions(app, db)
    private val photoId = "11111111-1111-1111-1111-111111111111"
    @Before fun setup() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        ProtectionSession.state.value.notebookIds.forEach { ProtectionSession.lockNotebook(it) }
        ProtectionSession.state.value.taskSyncIds.forEach { ProtectionSession.lockTask(it) }
    }
    @After fun teardown() { AppDatabase.closeInstance() }
    private suspend fun photo(id: Long, path: String): Long = db.attachmentDao().upsert(
        AttachmentEntity(syncId = photoId, noteId = id, filePath = path, mimeType = "image/jpeg", sizeBytes = 3))

    @Test fun `trash pauses both clocks and restores text tables tags and attachment`() = runBlocking {
        val now = System.currentTimeMillis()
        val original = NoteEntity(title = "Keep", content = "Body 😀", tablesData = "table", textFormat = "format", isTask = true,
            taskElapsedMillis = 2000, taskTimerStartedAt = now - 5000, taskCountdownMillis = 60_000,
            taskCountdownEndAt = now + 40_000, noteColor = "mint", isPinned = true)
        val id = db.noteDao().upsert(original)
        val file = File(app.filesDir, "attachments/$photoId").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        photo(id, file.path)
        val tag = db.tagDao().insert(TagEntity(name = "tag")); db.tagDao().attachToNote(NoteTagCrossRef(id, tag))
        assertEquals(1, actions.apply(setOf(id), NoteActions.Action.TRASH))
        val trashed = db.noteDao().getById(id)!!
        assertTrue(trashed.isDeleted); assertNull(trashed.taskTimerStartedAt); assertNull(trashed.taskCountdownEndAt)
        assertTrue(trashed.taskElapsedMillis >= 7000); assertTrue(trashed.taskCountdownMillis in 1..40_000)
        assertTrue(db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.All, NoteSort.BY_DATE)).first().isEmpty())
        assertEquals(listOf(id), db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.Trash, NoteSort.BY_DATE)).first().map { it.id })
        assertEquals(1, actions.apply(setOf(id), NoteActions.Action.RESTORE))
        val restored = db.noteDao().getById(id)!!
        assertEquals(original.content, restored.content); assertEquals(original.tablesData, restored.tablesData)
        assertEquals(original.textFormat, restored.textFormat); assertTrue(restored.isPinned); assertEquals("mint", restored.noteColor)
        assertNull(restored.taskTimerStartedAt); assertEquals(listOf("tag"), db.tagDao().getTagNamesForNote(id))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(1, db.attachmentDao().getByNoteId(id).size)
    }

    @Test fun `permanent delete wipes all content and cannot be restored or revived by peer`() = runBlocking {
        val id = db.noteDao().upsert(NoteEntity(title = "Secret", content = "Body", isDeleted = true, textFormat = "fmt",
            tablesData = "table", noteColor = "blue", isPinned = true, reminderAt = 7, passwordHash = "hash", passwordSalt = "salt"))
        val file = File(app.filesDir, "attachments/$photoId").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        photo(id, file.path)
        val source = RoomSyncDataSource(app, db); val before = source.getChangesSince(0).notes.single(); val meta = before.attachments.single()
        assertEquals(1, actions.apply(setOf(id), NoteActions.Action.PURGE))
        assertFalse(file.exists()); assertTrue(db.attachmentDao().getByNoteId(id).isEmpty())
        val wiped = db.noteDao().getById(id)!!
        assertTrue(wiped.isDeleted && wiped.isPurged); assertEquals("", wiped.content); assertEquals("", wiped.title)
        assertEquals("", wiped.tablesData); assertEquals("", wiped.textFormat); assertNull(wiped.passwordHash); assertNull(wiped.reminderAt)
        assertEquals(0, actions.apply(setOf(id), NoteActions.Action.RESTORE)); db.noteDao().restore(id)
        assertTrue(db.noteDao().getById(id)!!.isDeleted)
        val revived = before.copy(updatedAt = wiped.updatedAt + 10_000, deleted = false)
        assertEquals(0, source.applyChanges(ChangeSet(notes = listOf(revived))).appliedNotes)
        source.writeAttachmentBytes(meta, byteArrayOf(4, 5, 6)); assertFalse(file.exists())
        assertTrue(db.noteDao().getTrash().isEmpty())
        val dto = source.getChangesSince(0).notes.single()
        assertEquals(true, dto.purged); assertTrue(dto.attachments.isEmpty()); assertTrue(dto.tagNames.isEmpty())
        assertTrue(ConflictResolver.resolve(before, dto, true) is ConflictResolver.Resolution.ApplyRemote)
        assertTrue(ConflictResolver.resolve(dto, revived, true) is ConflictResolver.Resolution.KeepLocal)
    }

    @Test fun `received tombstone ignores supplied content and removes local attachments without conflict copy`() = runBlocking {
        val id = db.noteDao().upsert(NoteEntity(syncId = "peer", content = "unsynced", isPinned = true))
        val file = File(app.filesDir, "attachments/$photoId").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }; photo(id, file.path)
        val source = RoomSyncDataSource(app, db); val before = source.getChangesSince(0).notes.single()
        val result = source.applyChanges(ChangeSet(notes = listOf(before.copy(purged = true, deleted = false, updatedAt = 1))))
        assertEquals(1, result.appliedNotes); assertEquals(0, result.conflicts); assertTrue(result.missingAttachments.isEmpty())
        assertEquals("", db.noteDao().getById(id)!!.content); assertTrue(db.noteDao().getById(id)!!.isDeleted)
        assertFalse(file.exists()); assertTrue(db.noteDao().observeAll().first().isEmpty())
    }

    @Test fun `all trash actions respect current locks and support more than SQLite bind limit`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Locked", isProtected = true))
        val secret = db.noteDao().upsert(NoteEntity(title = "secret", notebookId = book, isDeleted = true))
        val task = db.noteDao().upsert(NoteEntity(syncId = "task", isTask = true, isProtected = true, isDeleted = true))
        val ids = (1..1001).map { db.noteDao().upsert(NoteEntity(title = "n$it", isDeleted = true)) }.toSet()
        assertEquals(1001, actions.apply(null, NoteActions.Action.RESTORE))
        assertTrue(db.noteDao().getById(secret)!!.isDeleted); assertTrue(db.noteDao().getById(task)!!.isDeleted)
        assertEquals(1001, actions.apply(ids, NoteActions.Action.TRASH)); assertEquals(1001, actions.apply(ids, NoteActions.Action.PURGE))
        ProtectionSession.unlockNotebook(book); ProtectionSession.unlockTask("task")
        assertEquals(2, actions.apply(null, NoteActions.Action.RESTORE))
    }

    @Test fun `pin sorts first and survives backup JSON exchange and concurrent sync acknowledgement`() = runBlocking {
        val first = db.noteDao().upsert(NoteEntity(title = "Z", noteColor = "orange", updatedAt = 1))
        db.noteDao().upsert(NoteEntity(title = "A", updatedAt = 2))
        val source = RoomSyncDataSource(app, db); val sent = source.getChangesSince(0)
        assertEquals(1, actions.apply(setOf(first), NoteActions.Action.PIN)); source.markSynced(sent)
        assertTrue(db.noteDao().getById(first)!!.dirty)
        for (sort in NoteSort.entries) assertEquals(first, db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.All, sort)).first().first().id)
        val bytes = ByteArrayOutputStream(); BackupManager.createBackup(app, bytes)
        val archive = File(app.cacheDir, "pinned.zip").apply { writeBytes(bytes.toByteArray()) }
        actions.apply(setOf(first), NoteActions.Action.UNPIN)
        assertTrue(BackupManager.restore(app, Uri.fromFile(archive)).isSuccess)
        assertTrue(db.noteDao().getById(first)!!.isPinned); assertEquals("orange", db.noteDao().getById(first)!!.noteColor)
        val dto = source.getChangesSince(0).notes.first { it.title == "Z" }
        val decoded = SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(ChangeSet(notes = listOf(dto.copy(syncId = "copy")))))) as SyncMessage.Changes
        source.applyChanges(decoded.set)
        assertTrue(db.noteDao().getBySyncId("copy")!!.isPinned); assertEquals("orange", db.noteDao().getBySyncId("copy")!!.noteColor)
    }

    @Test fun `failed batch rolls back rows without deleting original and shared canonical files survive purge`() = runBlocking {
        val first = db.noteDao().upsert(NoteEntity(isDeleted = true)); val second = db.noteDao().upsert(NoteEntity(isDeleted = true))
        val file = File(app.filesDir, "attachments/$photoId").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }; photo(first, file.path)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_purge BEFORE UPDATE ON notes WHEN NEW.id = $second AND NEW.isPurged = 1 BEGIN SELECT RAISE(ABORT, 'failed'); END")
        assertTrue(runCatching { actions.apply(linkedSetOf(first, second), NoteActions.Action.PURGE) }.isFailure)
        assertFalse(db.noteDao().getById(first)!!.isPurged); assertTrue(file.isFile); assertEquals(1, db.attachmentDao().getByNoteId(first).size)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_purge")
        db.attachmentDao().upsert(AttachmentEntity(noteId = second, filePath = File(file.parentFile, "../attachments/${file.name}").path, mimeType = "image/jpeg", sizeBytes = 3))
        actions.apply(setOf(first), NoteActions.Action.PURGE); assertTrue(file.isFile)
        actions.apply(setOf(second), NoteActions.Action.PURGE); assertFalse(file.isFile)
    }

    @Test fun `schema fourteen migration preserves documents and defaults flags without rebuilding data`() = runBlocking {
        val relative = "schemas/com.notessync.app.data.local.AppDatabase/14.json"
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
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(notes)", null).use { columns -> while (columns.moveToNext()) {
                if (columns.getInt(3) != 0 || columns.getInt(5) != 0) {
                    val name = columns.getString(1)
                    if (columns.getString(2) == "TEXT") values.put(name, if (name == "content") "Body 😀" else "")
                    else values.put(name, if (name == "id") 1L else 0L)
                }
            } }
            values.put("isDeleted", 1); values.put("updatedAt", 10_000L)
            values.put("taskElapsedMillis", 2000L); values.put("taskTimerStartedAt", 3000L)
            values.put("taskCountdownMillis", 30_000L); values.put("taskCountdownEndAt", 20_000L)
            old.insertOrThrow("notes", null, values); old.version = 14
        }
        val note = db.noteDao().getById(1)!!
        assertEquals("Body 😀", note.content); assertFalse(note.isPinned || note.isPurged); assertEquals("", note.noteColor)
        assertEquals(9000L, note.taskElapsedMillis); assertNull(note.taskTimerStartedAt)
        assertEquals(10_000L, note.taskCountdownMillis); assertNull(note.taskCountdownEndAt)
        assertEquals(AppDatabase.VERSION, db.openHelper.readableDatabase.version)
    }
}
