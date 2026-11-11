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
}

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
