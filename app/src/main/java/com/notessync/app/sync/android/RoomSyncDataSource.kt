package com.notessync.app.sync.android

import android.content.Context
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.security.ProtectionSession
import com.notessync.app.sync.core.ApplyStats
import com.notessync.app.sync.core.ConflictResolver
import com.notessync.app.sync.core.Hashing
import com.notessync.app.sync.core.SyncDataSource
import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.ChangeSet
import com.notessync.app.sync.protocol.NoteDto
import com.notessync.app.sync.protocol.NotebookDto
import java.io.File
import java.util.UUID

/** Реализация [SyncDataSource] поверх Room и файлового хранилища вложений. */
class RoomSyncDataSource(
    private val context: Context,
    private val db: AppDatabase
) : SyncDataSource {

    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    override suspend fun ensureSyncIds() {
        db.noteDao().getWithoutSyncId().forEach {
            db.noteDao().upsert(it.copy(syncId = UUID.randomUUID().toString()))
        }
        db.notebookDao().getWithoutSyncId().forEach {
            db.notebookDao().upsert(it.copy(syncId = UUID.randomUUID().toString()))
        }
    }

    override suspend fun getChangesSince(since: Long): ChangeSet {
        val notebooks = db.notebookDao().getChangedSince(since).map { it.toDto() }
        val notes = db.noteDao().getChangedSince(since).map { note ->
            // notebookId локальный — в обмен уходит глобальный syncId блокнота.
            val notebookSyncId = note.notebookId?.let { db.notebookDao().getById(it)?.syncId }
            val attachments = db.attachmentDao().getByNoteId(note.id).mapNotNull { it.toDto(note.syncId) }
            note.toDto(notebookSyncId, db.tagDao().getTagNamesForNote(note.id), attachments)
        }
        return ChangeSet(notebooks = notebooks, notes = notes)
    }

    override suspend fun applyChanges(changes: ChangeSet): ApplyStats {
        var appliedNotebooks = 0
        var appliedNotes = 0
        var conflicts = 0

        for (dto in changes.notebooks) {
            val local = db.notebookDao().getBySyncId(dto.syncId)
            if (local == null || dto.updatedAt > local.updatedAt) {
                val entity = NotebookEntity(
                    id = local?.id ?: 0,
                    syncId = dto.syncId,
                    name = dto.name,
                    isDeleted = dto.deleted,
                    isProtected = dto.isProtected,
                    passwordHash = dto.passwordHash,
                    passwordSalt = dto.passwordSalt,
                    createdAt = local?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = dto.updatedAt,
                    dirty = false
                )
                if (local == null) db.notebookDao().upsert(entity) else db.notebookDao().update(entity)
                ProtectionSession.lockNotebook(local?.id ?: entity.id)
                appliedNotebooks++
            }
        }

        for (dto in changes.notes) {
            val local = db.noteDao().getBySyncId(dto.syncId)
            val resolution = ConflictResolver.resolve(local?.toDto(null, emptyList(), emptyList()), dto, local?.dirty ?: false)
            when (resolution) {
                is ConflictResolver.Resolution.ApplyRemote -> {
                    applyNote(dto, local)
                    appliedNotes++
                }
                is ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy -> {
                    applyNote(dto, local)
                    applyNote(resolution.conflictCopy, existing = null)
                    appliedNotes++
                    conflicts++
                }
                ConflictResolver.Resolution.KeepLocal -> Unit
            }
        }

        val missing = changes.notes
            .flatMap { it.attachments }
            .filter { !hasAttachmentBytes(it.syncId) }
        return ApplyStats(appliedNotes, appliedNotebooks, conflicts, missing)
    }

    private suspend fun applyNote(dto: NoteDto, existing: NoteEntity?) {
        val entity = NoteEntity(
            id = existing?.id ?: 0,
            syncId = dto.syncId,
            title = dto.title,
            content = dto.content,
            notebookId = dto.notebookSyncId?.let { db.notebookDao().getBySyncId(it)?.id },
            isFavorite = dto.favorite,
            isDeleted = dto.deleted,
            createdAt = dto.createdAt,
            reminderAt = dto.reminderAt,
            dayKey = dto.dayKey,
            isTask = dto.isTask,
            isCompleted = dto.isCompleted,
            taskColor = dto.taskColor,
            taskElapsedMillis = dto.taskElapsedMillis,
            taskTimerStartedAt = dto.taskTimerStartedAt,
            taskCountdownMillis = dto.taskCountdownMillis,
            taskCountdownEndAt = dto.taskCountdownEndAt,
            taskCompletedAt = dto.taskCompletedAt,
            isProtected = dto.isProtected,
            passwordHash = dto.passwordHash,
            passwordSalt = dto.passwordSalt,
            updatedAt = dto.updatedAt,
            dirty = false
        )
        val localId = if (existing == null) {
            db.noteDao().upsert(entity)
        } else {
            db.noteDao().update(entity)
            existing.id
        }
        ProtectionSession.lockTask(dto.syncId)

        db.attachmentDao().deleteByNoteId(localId)
        for (meta in dto.attachments) {
            // Вложения с небезопасным идентификатором пропускаем (см. attachmentFile).
            if (!SAFE_ATTACHMENT_ID.matches(meta.syncId)) continue
            db.attachmentDao().upsert(
                AttachmentEntity(
                    syncId = meta.syncId,
                    noteId = localId,
                    filePath = attachmentFile(meta.syncId).absolutePath,
                    mimeType = meta.mimeType,
                    sizeBytes = meta.sizeBytes
                )
            )
        }

        db.tagDao().detachAllFromNote(localId)
        for (name in dto.tagNames) {
            val tag = db.tagDao().getByName(name) ?: TagEntity(name = name, dirty = false)
                .also { db.tagDao().insert(it) }
            db.tagDao().attachToNote(NoteTagCrossRef(noteId = localId, tagId = tag.id))
        }
    }

    override suspend fun hasAttachmentBytes(attachmentSyncId: String): Boolean =
        // Небезопасный id считаем «имеющимся», чтобы он никогда не запрашивался.
        !SAFE_ATTACHMENT_ID.matches(attachmentSyncId) ||
            attachmentFile(attachmentSyncId).let { it.isFile && it.length() > 0 }

    override suspend fun readAttachmentBytes(attachmentSyncId: String): ByteArray? =
        attachmentFile(attachmentSyncId).takeIf { it.isFile }?.readBytes()

    override suspend fun writeAttachmentBytes(meta: AttachmentDto, data: ByteArray) {
        attachmentFile(meta.syncId).writeBytes(data)
        db.attachmentDao().updateFile(meta.syncId, attachmentFile(meta.syncId).absolutePath, data.size.toLong())
    }

    override suspend fun markSynced(changeSet: ChangeSet) {
        db.noteDao().markSyncedBySyncId(changeSet.notes.map { it.syncId })
        db.notebookDao().markSyncedBySyncId(changeSet.notebooks.map { it.syncId })
    }

    private fun attachmentFile(syncId: String): File {
        // Аудит 2026-11-11: syncId приходит от пира и становится именем файла.
        // Без проверки «../../../…» позволял бы писать вне каталога вложений.
        require(SAFE_ATTACHMENT_ID.matches(syncId)) { "Некорректный syncId вложения" }
        return File(attachmentsDir, syncId)
    }

    private fun NotebookEntity.toDto() = NotebookDto(
        syncId = syncId,
        name = name,
        deleted = isDeleted,
        updatedAt = updatedAt,
        isProtected = isProtected,
        passwordHash = passwordHash,
        passwordSalt = passwordSalt
    )

    private fun NoteEntity.toDto(
        notebookSyncId: String?,
        tagNames: List<String>,
        attachments: List<AttachmentDto>
    ) = NoteDto(
        syncId = syncId,
        title = title,
        content = content,
        favorite = isFavorite,
        deleted = isDeleted,
        createdAt = createdAt,
        updatedAt = updatedAt,
        notebookSyncId = notebookSyncId,
        tagNames = tagNames,
        attachments = attachments,
        reminderAt = reminderAt,
        dayKey = dayKey,
        isTask = isTask,
        isCompleted = isCompleted,
        taskColor = taskColor,
        taskElapsedMillis = taskElapsedMillis,
        taskTimerStartedAt = taskTimerStartedAt,
        taskCountdownMillis = taskCountdownMillis,
        taskCountdownEndAt = taskCountdownEndAt,
        taskCompletedAt = taskCompletedAt,
        isProtected = isProtected,
        passwordHash = passwordHash,
        passwordSalt = passwordSalt
    )

    private fun AttachmentEntity.toDto(noteSyncId: String): AttachmentDto? {
        val file = File(filePath)
        if (!file.isFile) return null
        return AttachmentDto(
            syncId = syncId,
            noteSyncId = noteSyncId,
            mimeType = mimeType,
            sizeBytes = file.length(),
            sha256 = Hashing.sha256Hex(file.readBytes())
        )
    }

    companion object {
        // UUID вида 8-4-4-4-12: единственная допустимая форма syncId вложения.
        private val SAFE_ATTACHMENT_ID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}
