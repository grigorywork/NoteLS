package com.notessync.app.tasks

import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TaskDeadlineConflictTest {
    private fun note() = NoteDto("id", "Task", "", false, false, 1, 10, taskDeadline = TaskDeadlineDto(100))
    @Test fun `legacy peer cannot create a deadline conflict while explicit clear and changes preserve a dirty local copy`() {
        val local = note()
        assertEquals(ConflictResolver.Resolution.ApplyRemote, ConflictResolver.resolve(local, local.copy(updatedAt = 20, taskDeadline = null), true))
        listOf(TaskDeadlineDto(null), TaskDeadlineDto(200)).forEach { deadline ->
            val resolution = ConflictResolver.resolve(local, local.copy(updatedAt = 20, taskDeadline = deadline), true)
            assertTrue(resolution is ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy)
            assertEquals(local.taskDeadline, (resolution as ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy).conflictCopy.taskDeadline)
        }
    }
}
