package com.notessync.app.notes.subtasks

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.notessync.app.data.backup.EncryptedBackupFixture
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.subtasks.*
import com.notessync.app.settings.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.protocol.*
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
class SubtaskPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL) }
    @After fun after() { AppDatabase.closeInstance() }
    private suspend fun parent() = NoteEntity(syncId = "parent", title = "Original", content = "Body 😀", tablesData = "future opaque tables", textFormat = "opaque-format", createdAt = 7, updatedAt = 10, dirty = false)
        .let { it.copy(id = db.noteDao().upsert(it)) }
    private suspend fun rows(id: Long) = db.subtaskDao().get(id).map { it.item() }
    @Test fun `schema19 migration retains old documents and creates no implicit subtasks`() = runBlocking {
        val name = "schemas/com.notessync.app.data.local.AppDatabase/19.json"
        val schema = JSONObject(listOf(File(name),File("app/$name")).first { it.isFile }.readText()).getJSONObject("database")
        val path = app.getDatabasePath("notessync.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val table=e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=e.getJSONArray("indices");for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val values=ContentValues()
            old.rawQuery("PRAGMA table_info(notes)",null).use { c -> while(c.moveToNext()) if(c.getInt(3)!=0||c.getInt(5)!=0) {
                val key=c.getString(1)
                if(c.getString(2)=="TEXT") values.put(key,when(key){"syncId"->"legacy";"title"->"Legacy";"content"->"Unchanged 😀";"textFormat"->"styles";"tablesData"->"future tables";else->""})
                else values.put(key,if(key=="id") 7L else 0L)
            } }
            values.put("createdAt",123L);values.put("taskElapsedMillis",456L)
            old.insertOrThrow("notes",null,values);old.version=19
        }
        val note=db.noteDao().getById(7)!!
        assertEquals(20,db.openHelper.readableDatabase.version);assertEquals("Unchanged 😀",note.content)
        assertEquals("styles",note.textFormat);assertEquals("future tables",note.tablesData);assertEquals(123L,note.createdAt);assertEquals(456L,note.taskElapsedMillis)
        assertTrue(rows(7).isEmpty())
    }
    @Test fun `children inherit parent identity and notebook and survive encrypted backup with their exact timing`() = runBlocking {
        val book=db.notebookDao().upsert(NotebookEntity(name="Work"))
        val original=parent().copy(notebookId=book);db.noteDao().update(original)
        val children=listOf(Subtask("a","First 😀",elapsedMillis=987),Subtask("b","Second",completed=true))
        db.withTransaction { replaceSubtasks(db,original.id,children) }
        val bytes=ByteArrayOutputStream();EncryptedBackupFixture.createBackup(app,bytes)
        val file=File(app.cacheDir,"subtasks.nls").apply { writeBytes(bytes.toByteArray()) }
        db.subtaskDao().clear(original.id)
        assertTrue(EncryptedBackupFixture.restore(app,Uri.fromFile(file)).isSuccess)
        assertEquals(children,rows(original.id));assertEquals(original,db.noteDao().getById(original.id))
        assertEquals(1,db.noteDao().getChangedSince(0).size)
    }
    @Test fun `wire roundtrip includes children but legacy omission preserves them and explicit empty clears`() = runBlocking {
        val note=parent();val children=listOf(Subtask("a","Typed 😀",elapsedMillis=20))
        db.withTransaction { replaceSubtasks(db,note.id,children) }
        val peer=RoomSyncDataSource(app,db);val dto=peer.getChangesSince(0).notes.single()
        val wire=SyncMessage.decode(SyncMessage.encode(SyncMessage.Changes(ChangeSet(notes=listOf(dto.copy(syncId="second")))))) as SyncMessage.Changes
        peer.applyChanges(wire.set);val second=db.noteDao().getBySyncId("second")!!;assertEquals(children,rows(second.id))
        peer.applyChanges(ChangeSet(notes=listOf(dto.copy(title="Old client edit",updatedAt=20,subtasks=null))))
        assertEquals(children,rows(note.id));assertEquals("Old client edit",db.noteDao().getById(note.id)!!.title)
        peer.applyChanges(ChangeSet(notes=listOf(dto.copy(updatedAt=30,subtasks=emptyList()))));assertTrue(rows(note.id).isEmpty())
    }
    @Test fun `conflict copy keeps local children and child identities are scoped to their new parent`() = runBlocking {
        val note=parent().copy(dirty=true);db.noteDao().update(note)
        db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Local"))) }
        val peer=RoomSyncDataSource(app,db);val dto=peer.getChangesSince(0).notes.single()
        val stats=peer.applyChanges(ChangeSet(notes=listOf(dto.copy(updatedAt=20,subtasks=listOf(Subtask("a","Remote"))))))
        assertEquals(1,stats.conflicts);val notes=db.noteDao().getChangedSince(0)
        assertEquals(2,notes.size);assertEquals("Remote",rows(note.id).single().text)
        assertEquals("Local",rows(notes.single { it.syncId!=note.syncId }.id).single().text)
    }
    @Test fun `invalid remote child payload is rejected before any entity in the batch is applied`() = runBlocking {
        val note=parent();val peer=RoomSyncDataSource(app,db);val dto=peer.getChangesSince(0).notes.single()
        try { peer.applyChanges(ChangeSet(notes=listOf(dto.copy(syncId="valid-new"),dto.copy(syncId="bad-new",subtasks=listOf(Subtask("duplicate"),Subtask("duplicate"))))));fail("Invalid batch accepted") }
        catch (_:IllegalArgumentException) { }
        assertNull(db.noteDao().getBySyncId("valid-new"));assertEquals(note,db.noteDao().getById(note.id))
    }
    @Test fun `completion trash and permanent deletion pause or remove children without changing document fields`() = runBlocking {
        val now=System.currentTimeMillis();val note=parent().copy(isTask=true);db.noteDao().update(note)
        db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Keep",startedAt=now-100,countdownMillis=1000,countdownEndAt=now+1000))) }
        db.noteDao().update(note.copy(isCompleted=true));val paused=rows(note.id).single()
        assertNull(paused.startedAt);assertNull(paused.countdownEndAt);assertFalse(paused.completed);assertEquals("Body 😀",db.noteDao().getById(note.id)!!.content)
        db.noteDao().update(note.copy(isCompleted=false));assertNull(rows(note.id).single().startedAt)
        db.noteDao().update(note.copy(isDeleted=true));assertEquals("Keep",rows(note.id).single().text)
        db.noteDao().update(note.copy(isDeleted=true,isPurged=true));assertTrue(rows(note.id).isEmpty())
    }
    @Test fun `staged snapshot validation rejects malformed row order instead of silently dropping a child`() = runBlocking {
        val note=parent();db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Safe"))) }
        db.openHelper.writableDatabase.execSQL("UPDATE note_subtasks SET position = 9")
        try { validateSubtaskSnapshot(db.openHelper.readableDatabase) {};fail("Malformed snapshot accepted") } catch (_:IllegalArgumentException) { }
        assertEquals("Safe",rows(note.id).single().text)
    }
    @Test fun `malformed archived subtasks are rejected before current live note and children change`() = runBlocking {
        val note=parent();db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Archive"))) }
        db.openHelper.writableDatabase.execSQL("UPDATE note_subtasks SET position = 9")
        val bytes=ByteArrayOutputStream();EncryptedBackupFixture.createBackup(app,bytes)
        val file=File(app.cacheDir,"malformed-subtasks.nls").apply { writeBytes(bytes.toByteArray()) }
        db.openHelper.writableDatabase.execSQL("UPDATE note_subtasks SET position = 0, text = 'Current child'")
        db.noteDao().update(note.copy(content="Current body"))
        assertTrue(EncryptedBackupFixture.restore(app,Uri.fromFile(file)).isFailure)
        assertEquals("Current body",db.noteDao().getById(note.id)!!.content);assertEquals("Current child",rows(note.id).single().text)
    }
    @Test fun `child insertion failure during restore rolls back parent replacement too`() = runBlocking {
        val note=parent();db.withTransaction { replaceSubtasks(db,note.id,listOf(Subtask("a","Archive"))) }
        val bytes=ByteArrayOutputStream();EncryptedBackupFixture.createBackup(app,bytes)
        val file=File(app.cacheDir,"subtask-rollback.nls").apply { writeBytes(bytes.toByteArray()) }
        db.withTransaction { db.noteDao().update(note.copy(content="Current body"));replaceSubtasks(db,note.id,listOf(Subtask("a","Current child"))) }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_subtasks BEFORE INSERT ON note_subtasks BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(EncryptedBackupFixture.restore(app,Uri.fromFile(file)).isFailure)
        assertEquals("Current body",db.noteDao().getById(note.id)!!.content);assertEquals("Current child",rows(note.id).single().text)
    }
}
