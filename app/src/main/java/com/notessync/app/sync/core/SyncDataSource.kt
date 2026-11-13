package com.notessync.app.sync.core

import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.ChangeSet

/**
 * Источник данных для синхронизации. Абстракция над Room, чтобы ядро
 * протокола тестировалось на JVM без Android. Реализация для Room —
 * в sync/android/RoomSyncDataSource.
 */
interface SyncDataSource {

    /** Присваивает syncId записям, у которых он пуст (данные до миграции). */
    suspend fun ensureSyncIds()

    /** Дельта: всё, что менялось локально с момента [since] или помечено dirty. */
    suspend fun getChangesSince(since: Long): ChangeSet

    /**
     * Применяет чужой пакет изменений. Возвращает статистику.
     * Конфликтные копии включаются в [ApplyStats.conflicts].
     */
    suspend fun applyChanges(changes: ChangeSet): ApplyStats

    /** Есть ли файл вложения локально (для решения, запрашивать ли передачу). */
    suspend fun hasAttachmentBytes(attachmentSyncId: String): Boolean

    /** Байты вложения для отправки; null — файла нет. */
    suspend fun readAttachmentBytes(attachmentSyncId: String): ByteArray?

    /** Сохраняет полученные байты вложения и привязывает к записи. */
    suspend fun writeAttachmentBytes(meta: AttachmentDto, data: ByteArray)

    /** Снимает флаг dirty с успешно переданных записей. */
    suspend fun markSynced(changeSet: ChangeSet)

    /** Small acknowledgement snapshot; do not retain all note bodies for the whole connection. */
    suspend fun markAcknowledged(versions: SyncAcknowledgements) = markSynced(ChangeSet(
        notebooks = versions.notebooks.map { com.notessync.app.sync.protocol.NotebookDto(it.syncId, "", false, it.updatedAt) },
        notes = versions.notes.map { com.notessync.app.sync.protocol.NoteDto(it.syncId, "", "", false, false, 0, it.updatedAt) }
    ))
}

data class SyncVersion(val syncId: String, val updatedAt: Long)
data class SyncAcknowledgements(val notes: List<SyncVersion> = emptyList(), val notebooks: List<SyncVersion> = emptyList())

data class ApplyStats(
    val appliedNotes: Int = 0,
    val appliedNotebooks: Int = 0,
    val conflicts: Int = 0,
    /** Вложения, которых нет локально, — их байты нужно запросить у peer. */
    val missingAttachments: List<AttachmentDto> = emptyList()
)

data class SyncResult(
    val sentNotes: Int,
    val receivedNotes: Int,
    val conflicts: Int,
    val sentAttachments: Int,
    val receivedAttachments: Int,
    val bytesSent: Long,
    val bytesReceived: Long
)
