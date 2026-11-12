package com.notessync.app.notes

import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.ProtectionState
import com.notessync.app.security.visibleNotes
import org.junit.Assert.*
import org.junit.Test

class NoteVisibilityTest {
    @Test fun `locking either task or notebook hides an unchanged cached row without waiting for SQL`() {
        val book = NotebookEntity(id = 7, name = "Private", isProtected = true)
        val cached = listOf(NoteEntity(id = 1), NoteEntity(id = 2, syncId = "task", isProtected = true),
            NoteEntity(id = 3, notebookId = 7), NoteEntity(id = 4, notebookId = 7, syncId = "nested", isProtected = true))
        fun ids(state: ProtectionState) = visibleNotes(cached, listOf(book), state).map { it.id }
        assertEquals(listOf(1L), ids(ProtectionState()))
        assertEquals(listOf(1L, 3L), ids(ProtectionState(notebookIds = setOf(7))))
        assertEquals(listOf(1L, 2L), ids(ProtectionState(taskSyncIds = setOf("task", "nested"))))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(ProtectionState(setOf(7), setOf("task", "nested"))))
        assertEquals(listOf(1L), ids(ProtectionState()))
        assertEquals(4, cached.size)
    }
}
