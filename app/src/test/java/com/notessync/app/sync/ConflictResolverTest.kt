package com.notessync.app.sync

import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.core.ConflictResolver.Resolution
import com.notessync.app.sync.protocol.NoteDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private fun note(
        syncId: String = "same-id",
        title: String = "Заголовок",
        content: String = "Текст",
        updatedAt: Long = 1000
    ) = NoteDto(
        syncId = syncId, title = title, content = content,
        favorite = false, deleted = false, createdAt = 0, updatedAt = updatedAt
    )

    @Test
    fun `новая заметка применяется без вопросов`() {
        val result = ConflictResolver.resolve(local = null, remote = note(), localDirty = false)
        assertEquals(Resolution.ApplyRemote, result)
    }

    @Test
    fun `более свежая версия побеждает`() {
        val local = note(updatedAt = 1000)
        val remote = note(title = "Новее", updatedAt = 2000)
        // Локальная не менялась с прошлой синхронизации — просто принимаем удалённую.
        val result = ConflictResolver.resolve(local, remote, localDirty = false)
        assertEquals(Resolution.ApplyRemote, result)
    }

    @Test
    fun `устаревшая удалённая версия отклоняется`() {
        val local = note(updatedAt = 2000)
        val remote = note(title = "Старое", updatedAt = 1000)
        val result = ConflictResolver.resolve(local, remote, localDirty = true)
        assertEquals(Resolution.KeepLocal, result)
    }

    @Test
    fun `конфликт сохраняет локальную правку копией`() {
        val local = note(title = "Моя правка", updatedAt = 1000)
        val remote = note(title = "Чужая правка", updatedAt = 2000)
        val result = ConflictResolver.resolve(local, remote, localDirty = true)

        assertTrue(result is Resolution.ApplyRemoteKeepConflictCopy)
        val copy = (result as Resolution.ApplyRemoteKeepConflictCopy).conflictCopy
        assertTrue(copy.title.endsWith(ConflictResolver.CONFLICT_SUFFIX))
        assertNotEquals(local.syncId, copy.syncId) // копия — новая самостоятельная заметка
        assertEquals("Моя правка", copy.title.removeSuffix(ConflictResolver.CONFLICT_SUFFIX))
    }

    @Test
    fun `одинаковое содержимое не считается конфликтом`() {
        val local = note(updatedAt = 1000)
        val remote = note(updatedAt = 2000) // тот же текст, просто новее метка
        val result = ConflictResolver.resolve(local, remote, localDirty = true)
        assertEquals(Resolution.ApplyRemote, result)
    }

    @Test
    fun timerChangesAreContentConflicts() {
        val local = note(updatedAt = 1000).copy(taskElapsedMillis = 5_000L)
        val remote = note(updatedAt = 2000).copy(taskTimerStartedAt = 1_500L)
        val result = ConflictResolver.resolve(local, remote, localDirty = true)
        assertTrue(result is Resolution.ApplyRemoteKeepConflictCopy)
    }
}
