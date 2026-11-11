package com.notessync.app.sync

import com.notessync.app.sync.protocol.ChangeSet
import com.notessync.app.sync.protocol.NoteDto
import com.notessync.app.sync.protocol.NotebookDto
import com.notessync.app.sync.protocol.SyncMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSyncPayloadTest {
    @Test
    fun taskStateColorAndDaySurviveRoundTrip() {
        val note = NoteDto(
            syncId = "task-1",
            title = "Task",
            content = "Details",
            favorite = false,
            deleted = false,
            createdAt = 1,
            updatedAt = 2,
            dayKey = 20_000,
            isTask = true,
            isCompleted = true,
            taskColor = "orange",
            taskElapsedMillis = 65_000L,
            taskTimerStartedAt = 1_700_000_000_000L,
            taskCountdownMillis = 900_000L,
            taskCountdownEndAt = 1_700_000_900_000L,
            taskCompletedAt = 1_700_001_000_000L,
            isProtected = true,
            passwordHash = "hash",
            passwordSalt = "salt"
        )
        val message = SyncMessage.Changes(ChangeSet(notes = listOf(note)))

        val decoded = SyncMessage.decode(SyncMessage.encode(message)) as SyncMessage.Changes

        assertEquals(note, decoded.set.notes.single())
    }

    @Test
    fun oldPayloadUsesTaskDefaults() {
        val json = """
            {"type":"changes","set":{"notes":[{"syncId":"old","title":"Old","content":"","favorite":false,"deleted":false,"createdAt":1,"updatedAt":2}]}}
        """.trimIndent()

        val decoded = SyncMessage.decode(json.toByteArray()) as SyncMessage.Changes
        val note = decoded.set.notes.single()

        assertEquals(false, note.isTask)
        assertEquals(false, note.isCompleted)
        assertEquals("violet", note.taskColor)
        assertEquals(0L, note.taskElapsedMillis)
        assertEquals(null, note.taskTimerStartedAt)
        assertEquals(0L, note.taskCountdownMillis)
        assertEquals(null, note.taskCountdownEndAt)
        assertEquals(null, note.taskCompletedAt)
        assertEquals(false, note.isProtected)
        assertEquals(null, note.passwordHash)
        assertEquals(null, note.passwordSalt)
    }

    @Test
    fun notebookProtectionSurvivesRoundTrip() {
        val notebook = NotebookDto(
            syncId = "notebook-1",
            name = "Private",
            deleted = false,
            updatedAt = 10,
            isProtected = true,
            passwordHash = "hash",
            passwordSalt = "salt"
        )
        val message = SyncMessage.Changes(ChangeSet(notebooks = listOf(notebook)))

        val decoded = SyncMessage.decode(SyncMessage.encode(message)) as SyncMessage.Changes

        assertEquals(notebook, decoded.set.notebooks.single())
    }
}
