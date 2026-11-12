package com.notessync.app.sync.core

import com.notessync.app.sync.protocol.NoteDto
import java.util.UUID

/**
 * Разрешение конфликтов: «последняя правка побеждает».
 * Если локальная версия тоже менялась с последней синхронизации и отличается
 * по содержимому — она не теряется, а сохраняется конфликтной копией.
 */
object ConflictResolver {

    sealed interface Resolution {
        /** Применить удалённую версию (локальной нет или она устарела без своих правок). */
        data object ApplyRemote : Resolution

        /** Применить удалённую, а локальную сохранить копией с новым syncId. */
        data class ApplyRemoteKeepConflictCopy(val conflictCopy: NoteDto) : Resolution

        /** Локальная версия новее — удалённую отклонить. */
        data object KeepLocal : Resolution
    }

    fun resolve(local: NoteDto?, remote: NoteDto, localDirty: Boolean): Resolution {
        if (local == null) return Resolution.ApplyRemote
        // A permanent deletion never creates a content-bearing conflict copy or revives.
        if (local.purged == true) return Resolution.KeepLocal
        if (remote.purged == true) return Resolution.ApplyRemote
        if (remote.updatedAt <= local.updatedAt) return Resolution.KeepLocal
        return if (localDirty && contentDiffers(local, remote)) {
            Resolution.ApplyRemoteKeepConflictCopy(conflictCopyOf(local))
        } else {
            Resolution.ApplyRemote
        }
    }

    private fun contentDiffers(a: NoteDto, b: NoteDto): Boolean =
        a.title != b.title || a.content != b.content || a.textFormat != b.textFormat || (b.tablesData != null && a.tablesData.orEmpty() != b.tablesData) || a.taskPriority != b.taskPriority || a.favorite != b.favorite ||
            a.deleted != b.deleted || (b.pinned != null && a.pinned != b.pinned) ||
            (b.noteColor != null && a.noteColor.orEmpty() != b.noteColor) || a.reminderAt != b.reminderAt ||
            a.dayKey != b.dayKey || a.isTask != b.isTask || a.isCompleted != b.isCompleted ||
            a.taskColor != b.taskColor || a.taskElapsedMillis != b.taskElapsedMillis ||
            a.taskTimerStartedAt != b.taskTimerStartedAt || a.isProtected != b.isProtected ||
            a.passwordHash != b.passwordHash || a.passwordSalt != b.passwordSalt ||
            a.tagNames.sorted() != b.tagNames.sorted()

    private fun conflictCopyOf(local: NoteDto): NoteDto = local.copy(
        syncId = UUID.randomUUID().toString(),
        title = local.title.ifBlank { "Без названия" } + CONFLICT_SUFFIX,
        // Копия — новая независимая заметка: она сама уедет на другое устройство
        // при следующей синхронизации.
        updatedAt = System.currentTimeMillis()
    )

    const val CONFLICT_SUFFIX = " (конфликт)"
}
