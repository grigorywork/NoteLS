package com.notessync.app.sync.audit

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.security.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.protocol.ChangeSet
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeletedNotebookRelationsAuditProbe {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    @Before fun before() { AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll() }
    @After fun after() { AppDatabase.closeInstance(); ProtectionSession.lockAll() }
    private suspend fun fixture(): Pair<NotebookEntity, NoteEntity> {
        val record = PasswordProtector.create("PUBLIC SYNTHETIC NOTEBOOK PASSWORD")
        val book = NotebookEntity(id = 1, name = "Container", isProtected = true, passwordHash = record.hash, passwordSalt = record.salt, dirty = false)
        db.notebookDao().upsert(book)
        val note = NoteEntity(id = 1, title = "Kept note", content = "Complete fixture body", notebookId = 1, dirty = false)
        db.noteDao().upsert(note); return book to note
    }
    @Test fun `committed notebook deletion detaches children even when a later note update fails`() = runBlocking {
        val (book, note) = fixture(); val source = RoomSyncDataSource(app, db)
        val snapshot = source.getChangesSince(Long.MIN_VALUE)
        val incoming = snapshot.copy(notebooks = listOf(snapshot.notebooks.single().copy(deleted = true, updatedAt = book.updatedAt + 1)),
            notes = listOf(snapshot.notes.single().copy(notebookSyncId = null, content = "Later incoming edit", updatedAt = note.updatedAt + 2)))
        // Notebook-only canonical detach is allowed; fail the later document UPDATE transaction.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_incoming_body BEFORE UPDATE OF content ON notes BEGIN SELECT RAISE(ABORT, 'synthetic later body failure'); END")
        assertTrue(runCatching { source.applyChanges(incoming) }.isFailure)
        assertTrue(db.notebookDao().getById(1)!!.isDeleted)
        assertTrue(db.notebookDao().observeAll().first().isEmpty())
        val current = db.noteDao().getById(1)!!; assertEquals(note.content, current.content)
        assertNull("A committed deleted container cannot keep its notes hidden behind an unavailable lock", current.notebookId)
        assertEquals(listOf(1L), db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.All, NoteSort.BY_DATE)).first().map { it.id })
    }
    @Test fun `incoming note cannot relink a deleted protected notebook`() = runBlocking {
        val (book, note) = fixture(); val source = RoomSyncDataSource(app, db)
        val snapshot = source.getChangesSince(Long.MIN_VALUE)
        db.notebookDao().update(book.copy(isDeleted = true))
        source.applyChanges(ChangeSet(notes = listOf(snapshot.notes.single().copy(content = "Incoming body", updatedAt = note.updatedAt + 2))))
        val current = db.noteDao().getById(1)!!
        assertEquals("Incoming body", current.content)
        assertNull("Deleted notebook identity must not reintroduce its inaccessible parent relation", current.notebookId)
    }
    @Test fun `reopening an existing valid database repairs tombstoned parent relation without changing document version`() = runBlocking {
        val (book, note) = fixture(); db.notebookDao().update(book.copy(isDeleted = true))
        AppDatabase.closeInstance()
        val current = db.noteDao().getById(1)!!
        assertEquals(note.content, current.content); assertEquals(note.syncId, current.syncId)
        assertEquals(note.updatedAt, current.updatedAt); assertEquals(note.dirty, current.dirty)
        assertNull("Existing partial deletion must not make a note permanently inaccessible", current.notebookId)
    }
}
