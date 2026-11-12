package com.notessync.app.notes

import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NoteSort
import com.notessync.app.ui.notes.NotesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SQL списка заметок: фильтр + поиск + сортировка (Этап 2). */
class NotesQueryTest {

    @Test
    fun `все заметки по дате`() {
        val q = NotesViewModel.buildQuery("", NoteFilter.All, NoteSort.BY_DATE)
        assertTrue(q.sql.contains("notes.isDeleted = 0"))
        assertTrue(q.sql.contains("notes.isPurged = 0"))
        assertTrue(q.sql.contains("notes.isProtected = 0"))
        assertTrue(q.sql.contains("notebooks WHERE isProtected = 1"))
        assertTrue(q.sql.contains("ORDER BY notes.isPinned DESC, notes.updatedAt DESC"))
        assertEquals(0, q.argCount)
    }

    @Test
    fun tasksFilterUsesTaskFlag() {
        val query = NotesViewModel.buildQuery("", NoteFilter.Tasks, NoteSort.BY_DATE)
        assertTrue(query.sql.contains("notes.isTask = 1"))
        assertEquals(0, query.argCount)
    }

    @Test
    fun unlockedProtectionUsesBoundParameters() {
        val query = NotesViewModel.buildQuery(
            search = "",
            filter = NoteFilter.All,
            sort = NoteSort.BY_DATE,
            unlockedNotebookIds = setOf(7L, 9L),
            unlockedTaskIds = setOf("task-1")
        )

        assertTrue(query.sql.contains("notes.syncId IN (?)"))
        assertTrue(query.sql.contains("notes.notebookId IN (?,?)"))
        assertEquals(3, query.argCount)
    }

    @Test
    fun `избранное по имени`() {
        val q = NotesViewModel.buildQuery("", NoteFilter.Favorites, NoteSort.BY_NAME)
        assertTrue(q.sql.contains("notes.isFavorite = 1"))
        assertTrue(q.sql.contains("ORDER BY notes.isPinned DESC, notes.title COLLATE NOCASE ASC"))
    }

    @Test
    fun `блокнот фильтрует по id параметром`() {
        val q = NotesViewModel.buildQuery("", NoteFilter.Notebook(42, "Работа"), NoteSort.BY_DATE)
        assertTrue(q.sql.contains("notes.notebookId = ?"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `тег фильтрует подзапросом`() {
        val q = NotesViewModel.buildQuery("", NoteFilter.Tag("дом"), NoteSort.BY_DATE)
        assertTrue(q.sql.contains("note_tag_cross_ref"))
        assertEquals(1, q.argCount)
    }

    @Test
    fun `поиск добавляет два связанных параметра`() {
        val q = NotesViewModel.buildQuery("  текст  ", NoteFilter.All, NoteSort.BY_DATE)
        assertTrue(q.sql.contains("notes.title LIKE '%' || ? || '%'"))
        assertEquals(2, q.argCount)
    }

    @Test
    fun lockedTaskPlaceholdersHideProtectedNotebookContents() {
        val query = NotesViewModel.buildLockedTasksQuery(
            unlockedNotebookIds = setOf(7L),
            unlockedTaskIds = setOf("task-1", "task-2")
        )

        assertTrue(query.sql.contains("notes.isTask = 1 AND notes.isProtected = 1"))
        assertTrue(query.sql.contains("notes.syncId NOT IN (?,?)"))
        assertTrue(query.sql.contains("notebooks WHERE isProtected = 1"))
        assertTrue(query.sql.contains("notes.notebookId IN (?)"))
        assertEquals(3, query.argCount)
    }

    @Test
    fun `поиск экранирует служебные символы LIKE`() {
        val q = NotesViewModel.buildQuery("100%_\\", NoteFilter.All, NoteSort.BY_DATE)
        assertTrue(q.sql.contains("ESCAPE '\\'"))
        // %, _ и \ экранированы обратным слэшем — ищется литеральная строка.
        val bound = mutableMapOf<Int, String>()
        q.bindTo(object : androidx.sqlite.db.SupportSQLiteProgram {
            override fun bindString(index: Int, value: String) { bound[index] = value }
            override fun bindNull(index: Int) = Unit
            override fun bindLong(index: Int, value: Long) = Unit
            override fun bindDouble(index: Int, value: Double) = Unit
            override fun bindBlob(index: Int, value: ByteArray) = Unit
            override fun clearBindings() = Unit
            override fun close() = Unit
        })
        assertEquals("100\\%\\_\\\\", bound[1])
        assertEquals(bound[1], bound[2])
    }

    @Test
    fun `escapeLike не трогает обычный текст`() {
        assertEquals("привет мир", NotesViewModel.escapeLike("привет мир"))
        assertEquals("50\\%", NotesViewModel.escapeLike("50%"))
        assertEquals("a\\_b", NotesViewModel.escapeLike("a_b"))
    }
}
