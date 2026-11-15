package com.notessync.app.notes

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.notes.diary.DiaryEntries
import com.notessync.app.notes.tables.*
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
import com.notessync.app.ui.calendar.CalendarViewModel
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NavigationDataTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL); setArchiveCompletedTasks(false) }
    }
    @After fun after() {
        models.clear(); SettingsRepository.get(app).setArchiveCompletedTasks(false)
        ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain()
    }
    private suspend fun rows(filter: NoteFilter, archive: Boolean = false, books: Set<Long> = emptySet(), ids: Set<String> = emptySet()) =
        db.noteDao().rawQuery(NotesViewModel.buildQuery("", filter, NoteSort.BY_DATE, books, ids, archiveCompleted = archive)).first()
    private suspend fun action(vm: ViewModel, block: () -> Unit) {
        val parent = vm.viewModelScope.coroutineContext[Job]!!; val old = parent.children.toSet()
        block()
        withTimeout(15_000) { parent.children.filter { it !in old }.toList().joinAll() }
    }
    @Test fun `completed section is a view and unchecking preserves original notebook dates document and attachment`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Work"))
        val tag = db.tagDao().insert(TagEntity(name = "Important"))
        val table = NoteTables().add("preserved", 2, 2).editCell(TableCellRef("preserved", 0, 0), "Keep table").encode()
        val original = NoteEntity(title = "Completed", content = "body".repeat(5000), tablesData = table,
            notebookId = book, isFavorite = true, isTask = true, isCompleted = true,
            createdAt = 10, updatedAt = 20, dayKey = 123, taskDueAt = 30, taskCompletedAt = 25)
        val saved = original.copy(id = db.noteDao().upsert(original))
        db.tagDao().attachToNote(NoteTagCrossRef(saved.id, tag))
        val file = app.filesDir.resolve("keep-attachment.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        db.attachmentDao().upsert(AttachmentEntity(noteId = saved.id, filePath = file.path,
            mimeType = "application/octet-stream", sizeBytes = 4))
        val attachment = db.attachmentDao().getByNoteId(saved.id).single()
        db.noteDao().upsert(NoteEntity(title = "Plain"))
        db.noteDao().upsert(NoteEntity(title = "Active", isTask = true))
        val trash = db.noteDao().upsert(NoteEntity(isTask = true, isCompleted = true, isDeleted = true))
        db.noteDao().upsert(NoteEntity(isTask = true, isCompleted = true, isPurged = true))
        assertFalse(SettingsRepository.get(app).state.value.archiveCompletedTasks)
        val filters = listOf(NoteFilter.All, NoteFilter.Tasks, NoteFilter.Favorites,
            NoteFilter.Notebook(book, "Work"), NoteFilter.Tag("Important"))
        for (filter in filters) assertTrue(rows(filter).any { it.id == saved.id })
        SettingsRepository.get(app).setArchiveCompletedTasks(true)
        for (filter in filters) assertFalse(rows(filter, true).any { it.id == saved.id })
        assertEquals(listOf(saved.id), rows(NoteFilter.Completed, true).map { it.id })
        assertEquals(listOf(trash), rows(NoteFilter.Trash, true).map { it.id })
        assertEquals(saved, db.noteDao().getById(saved.id))
        val vm = NotesViewModel(app).also { models.put("home", it) }
        val card = db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.Completed, NoteSort.BY_DATE, archiveCompleted = true)).first().single()
        action(vm) { vm.toggleCompleted(card) }
        val after = db.noteDao().getById(saved.id)!!
        assertFalse(after.isCompleted); assertNull(after.taskCompletedAt)
        assertEquals(saved.copy(isCompleted = false, taskCompletedAt = null, updatedAt = after.updatedAt), after)
        assertEquals(attachment, db.attachmentDao().getByNoteId(saved.id).single())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
        assertTrue(rows(NoteFilter.Completed, true).isEmpty())
        for (filter in filters) assertTrue(rows(filter, true).any { it.id == saved.id })
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes").use { it.moveToFirst(); assertEquals(5, it.getInt(0)) }
    }
    @Test fun `completed filtering retains protection gates and closed cards remain metadata only`() = runBlocking {
        val book = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true))
        val publicId = db.noteDao().upsert(NoteEntity(isTask = true, isCompleted = true))
        val secret = NoteEntity(title = "SECRET", isTask = true, isCompleted = true, isProtected = true)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val parent = db.noteDao().upsert(NoteEntity(notebookId = book, isTask = true, isCompleted = true))
        val both = NoteEntity(notebookId = book, isTask = true, isCompleted = true, isProtected = true)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val active = db.noteDao().upsert(NoteEntity(isTask = true, isProtected = true))
        val converted = db.noteDao().upsert(NoteEntity(isTask = false, isCompleted = true))
        assertEquals(setOf(publicId), rows(NoteFilter.Completed, true).map { it.id }.toSet())
        assertEquals(setOf(publicId, secret.id), rows(NoteFilter.Completed, ids = setOf(secret.syncId, both.syncId)).map { it.id }.toSet())
        assertEquals(setOf(publicId, secret.id, parent, both.id), rows(NoteFilter.Completed, true, setOf(book), setOf(secret.syncId, both.syncId)).map { it.id }.toSet())
        assertTrue(rows(NoteFilter.All, true).any { it.id == converted })
        val doneQuery = NotesViewModel.buildLockedTaskTargetsQuery(completed = true)
        assertEquals(listOf(secret.id), db.noteDao().rawLockedTaskTargets(doneQuery).first().map { it.id })
        assertEquals(listOf(active), db.noteDao().rawLockedTaskTargets(NotesViewModel.buildLockedTaskTargetsQuery(completed = false)).first().map { it.id })
        db.openHelper.readableDatabase.query(doneQuery).use {
            assertEquals(setOf("id", "syncId", "notebookId", "isDeleted"), it.columnNames.toSet())
        }
    }
    @Test fun `journal entries use the local date and regular synced tags without changing other notes`() = runBlocking {
        val clock = Instant.parse("2026-11-15T23:30:00Z").toEpochMilli()
        val untouched = NoteEntity(title = "Keep", content = "Unrelated").let { it.copy(id = db.noteDao().upsert(it)) }
        val first = DiaryEntries.create(db, clock, ZoneId.of("UTC"))
        val second = DiaryEntries.create(db, clock, ZoneId.of("Asia/Tokyo"))
        assertEquals("15.11.2026", first.title); assertEquals("16.11.2026", second.title)
        assertEquals(LocalDate.of(2026, 11, 15).toEpochDay(), first.dayKey)
        assertEquals(LocalDate.of(2026, 11, 16).toEpochDay(), second.dayKey)
        assertNotEquals(first.id, second.id); assertNotEquals(first.syncId, second.syncId)
        assertFalse(first.isTask); assertFalse(first.isProtected); assertNull(first.notebookId)
        assertEquals(listOf(DiaryEntries.TAG_NAME), db.tagDao().getTagNamesForNote(first.id))
        assertEquals(2, db.tagDao().getForSyncNotes(listOf(first.id, second.id)).size)
        assertEquals(setOf(first.id, second.id), rows(NoteFilter.Tag(DiaryEntries.TAG_NAME)).map { it.id }.toSet())
        assertEquals(untouched, db.noteDao().getById(untouched.id))
    }
    @Test fun `journal revives the existing tag identity with a monotonic timestamp`() = runBlocking {
        val tag = TagEntity(name = DiaryEntries.TAG_NAME, isDeleted = true, updatedAt = 1000)
        val id = db.tagDao().insert(tag)
        val note = DiaryEntries.create(db, 500, ZoneId.of("UTC"))
        val after = db.tagDao().getByName(DiaryEntries.TAG_NAME)!!
        assertEquals(id, after.id); assertEquals(tag.syncId, after.syncId)
        assertFalse(after.isDeleted); assertEquals(1001L, after.updatedAt); assertTrue(after.dirty)
        assertEquals(listOf(DiaryEntries.TAG_NAME), db.tagDao().getTagNamesForNote(note.id))
    }
    @Test fun `calendar completion reads the latest document instead of overwriting it from an old row`() = runBlocking {
        val old = NoteEntity(title = "Old", content = "Old body", isTask = true, updatedAt = 100)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val table = NoteTables().add("new", 1, 1).editCell(TableCellRef("new", 0, 0), "Fresh table").encode()
        val fresh = old.copy(title = "Fresh", content = "fresh".repeat(5000), tablesData = table, updatedAt = 200,
            createdAt = 50, dayKey = 123, taskDueAt = 999, taskElapsedMillis = 25)
        db.noteDao().update(fresh)
        val vm = CalendarViewModel(app).also { models.put("calendar", it) }
        action(vm) { vm.toggleCompleted(old) }
        val after = db.noteDao().getById(old.id)!!
        assertTrue(after.isCompleted)
        assertEquals(fresh.copy(isCompleted = true, taskCompletedAt = after.taskCompletedAt, updatedAt = after.updatedAt), after)
    }
    @Test fun `calendar rejects stale identity completion state and freshly locked parent`() = runBlocking {
        val vm = CalendarViewModel(app).also { models.put("calendar", it) }
        val book = db.notebookDao().upsert(NotebookEntity(name = "Locked", isProtected = true))
        for (case in 0..2) {
            val old = NoteEntity(title = "Old", isTask = true).let { it.copy(id = db.noteDao().upsert(it)) }
            val fresh = when (case) {
                0 -> old.copy(syncId = "different-identity", content = "New identity")
                1 -> old.copy(isCompleted = true, content = "Already completed")
                else -> old.copy(notebookId = book, content = "Now locked")
            }
            db.noteDao().update(fresh)
            action(vm) { vm.toggleCompleted(old) }
            assertEquals(fresh, db.noteDao().getById(old.id))
        }
    }
    @Test fun `agenda crossing a month loads the selected day and keeps protected entries hidden`() = runBlocking {
        val a = LocalDate.of(2025, 1, 31); val b = LocalDate.of(2025, 2, 28)
        val first = db.noteDao().upsert(NoteEntity(title = "January", dayKey = a.toEpochDay()))
        val next = db.noteDao().upsert(NoteEntity(title = "February", dayKey = b.toEpochDay()))
        val book = db.notebookDao().upsert(NotebookEntity(name = "Private", isProtected = true))
        db.noteDao().upsert(NoteEntity(title = "Hidden", notebookId = book, dayKey = b.toEpochDay()))
        val vm = CalendarViewModel(app).also { models.put("calendar", it) }
        vm.selectAgendaDate(a)
        withTimeout(10_000) { vm.state.first { it.selectedNotes.any { n -> n.id == first } } }
        vm.selectAgendaDate(b)
        val state = withTimeout(10_000) { vm.state.first { it.selectedDate == b && it.selectedNotes.any { n -> n.id == next } } }
        assertEquals(YearMonth.of(2025, 2), state.month)
        assertEquals(listOf(next), state.selectedNotes.map { it.id })
    }
}
