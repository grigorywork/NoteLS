package com.notessync.app.notes

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FirstPhotoQueryTest {
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `photo metadata catalog skips audio and follows removal and arrival without depending on note visibility`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        val db = AppDatabase.get(app); val dao = db.attachmentDao()
        val first = db.noteDao().upsert(NoteEntity(title = "First"))
        val second = db.noteDao().upsert(NoteEntity(title = "Second"))
        val deleted = db.noteDao().upsert(NoteEntity(title = "Deleted", isDeleted = true))
        dao.upsert(AttachmentEntity(noteId = first, filePath = "audio", mimeType = "audio/mpeg", sizeBytes = 3))
        val image = dao.upsert(AttachmentEntity(noteId = first, filePath = "photo", mimeType = "image/png", sizeBytes = 3))
        val next = dao.upsert(AttachmentEntity(noteId = first, filePath = "next-photo", mimeType = "image/jpeg", sizeBytes = 3))
        dao.upsert(AttachmentEntity(noteId = second, filePath = "second", mimeType = "image/jpeg", sizeBytes = 3))
        dao.upsert(AttachmentEntity(noteId = deleted, filePath = "deleted", mimeType = "image/jpeg", sizeBytes = 3))
        val initial = dao.observeFirstPhotos().first().associateBy { it.noteId }
        assertEquals(setOf(first, second, deleted), initial.keys); assertEquals(image, initial[first]!!.id)
        dao.deleteById(image)
        val replacement = dao.observeFirstPhotos().first().first { it.noteId == first }
        assertEquals(next, replacement.id)
        dao.updateFile(replacement.syncId, replacement.filePath, replacement.sizeBytes)
        assertEquals(1L, dao.observeFirstPhotos().first().first { it.noteId == first }.previewRevision)
        db.noteDao().softDelete(first)
        // Visibility is handled by NotesViewModel; the photo catalog needs no note-table observer.
        assertEquals(setOf(first, second, deleted), dao.observeFirstPhotos().first().map { it.noteId }.toSet())
    }
}
