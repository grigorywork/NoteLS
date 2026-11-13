package com.notessync.app.sync.android

import com.notessync.app.i18n.uiText

import android.content.Context
import androidx.room.withTransaction
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.notes.photos.PhotoDisplaySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import com.notessync.app.data.attachments.PhotoFileWriter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import com.notessync.app.sync.core.SyncLimits
import com.notessync.app.sync.protocol.AttachmentDto
import com.notessync.app.sync.protocol.ChangeSet
import com.notessync.app.sync.protocol.NoteDto
import com.notessync.app.sync.protocol.NotebookDto
import com.notessync.app.sync.core.StreamingSyncDataSource
import com.notessync.app.sync.core.SyncAttachmentSource
import com.notessync.app.sync.core.SyncAttachmentSink
import com.notessync.app.sync.core.VerifiedAttachmentSink
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.util.UUID

/** Реализация [SyncDataSource] поверх Room и файлового хранилища вложений. */
class RoomSyncDataSource(
    private val context: Context,
    private val db: AppDatabase,
    private val attachmentDigest: suspend (File) -> String = { file ->
        val work = currentCoroutineContext()
        file.inputStream().use { Hashing.sha256Hex(it, SyncLimits.ATTACHMENT_BYTES) { work.ensureActive() } }
    }
) : StreamingSyncDataSource {

    private val attachmentsDir = File(context.filesDir, "attachments")
    private val incomingDir = File(attachmentsDir, ".sync-incoming")
    private val sweptIncoming = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun ensureSyncIds() {
        // UPDATE preserves dependent rows; INSERT OR REPLACE would cascade-delete attachments/tags.
        assignMissingIds({ db.noteDao().idsWithoutSyncId() }) { id ->
            db.noteDao().assignSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }
        assignMissingIds({ db.notebookDao().idsWithoutSyncId() }) { id ->
            db.notebookDao().assignSyncId(id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }
        assignMissingIds({ db.attachmentDao().idsWithoutSyncId() }) { id ->
            db.withTransaction {
                if (db.attachmentDao().assignSyncId(id, UUID.randomUUID().toString()) != 0) {
                    db.attachmentDao().markParentIdentityChanged(id, System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun assignMissingIds(load: suspend () -> List<Long>, assign: suspend (Long) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = load()
            if (ids.isEmpty()) return
            for (id in ids) { currentCoroutineContext().ensureActive(); assign(id) }
        }
    }

    override suspend fun getChangesSince(since: Long): ChangeSet = withContext(Dispatchers.IO) {
        data class Captured(val note: NoteEntity, val notebook: String?, val tags: List<String>, val files: List<AttachmentEntity>)
        // One consistent SQL snapshot; no file open/hash is performed while holding this transaction.
        val snapshot = db.withTransaction {
            val notebooks = db.notebookDao().getChangedSince(since).map { it.toDto() }
            val rows = db.noteDao().getSyncSnapshotSince(since)
            val notes = ArrayList<Captured>(rows.size)
            for (group in rows.chunked(200)) {
                val ids = group.map { it.note.id }
                val files = db.attachmentDao().getForSyncNotes(ids).groupBy { it.noteId }
                val tags = db.tagDao().getForSyncNotes(ids).groupBy { it.noteId }
                for (row in group) notes.add(Captured(row.note, row.linkedNotebookSyncId,
                    tags[row.note.id].orEmpty().map { it.name }, files[row.note.id].orEmpty()))
            }
            notebooks to notes
        }
        val notes = ArrayList<NoteDto>(snapshot.second.size)
        for (row in snapshot.second) {
            currentCoroutineContext().ensureActive()
            notes.add(row.note.toDto(row.notebook, row.tags, row.files.mapNotNull { it.toDto(row.note.syncId) }))
        }
        ChangeSet(notebooks = snapshot.first, notes = notes)
    }

    override suspend fun applyChanges(changes: ChangeSet): ApplyStats = withContext(Dispatchers.IO) {
        SyncLimits.changes(changes)
        var appliedNotebooks = 0
        var appliedNotes = 0
        var conflicts = 0

        val discardedFiles = mutableListOf<String>()
        try {
            for (dto in changes.notebooks) syncTransaction {
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
                    ProtectionSession.lockNotebook(local?.id ?: entity.id)
                    if (local == null) db.notebookDao().upsert(entity) else db.notebookDao().update(entity)
                    appliedNotebooks++
                }
            }

            for (dto in changes.notes) syncTransaction {
                val local = db.noteDao().getBySyncId(dto.syncId)
                val mayConflict = local != null && local.dirty && !local.isPurged && dto.purged != true && dto.updatedAt > local.updatedAt
                val localFiles = if (mayConflict) db.attachmentDao().getByNoteId(local!!.id) else emptyList()
                val localSnapshot = local?.toDto(
                    if (mayConflict) local.notebookId?.let { db.notebookDao().getById(it)?.syncId } else null,
                    if (mayConflict) db.tagDao().getTagNamesForNote(local.id) else emptyList(),
                    if (mayConflict) localFiles.mapNotNull { it.toDto(local.syncId) } else emptyList())
                val resolution = ConflictResolver.resolve(localSnapshot, dto, local?.dirty ?: false)
                when (resolution) {
                    is ConflictResolver.Resolution.ApplyRemote -> {
                        applyNote(dto, local, discardedFiles)
                        appliedNotes++
                    }
                    is ConflictResolver.Resolution.ApplyRemoteKeepConflictCopy -> {
                        applyNote(dto, local, discardedFiles)
                        applyNote(resolution.conflictCopy, existing = null, discardedFiles = discardedFiles, localConflict = true)
                        preserveConflictFiles(localSnapshot!!, resolution.conflictCopy, localFiles, discardedFiles)
                        appliedNotes++
                        conflicts++
                    }
                    ConflictResolver.Resolution.KeepLocal -> Unit
                }
            }

        } finally {
            withContext(NonCancellable) { runCatching { db.withTransaction { NoteActions.deleteUnreferencedFiles(context, db, discardedFiles) } } }
        }
        val missing = changes.notes
            .filter { dto -> db.noteDao().getBySyncId(dto.syncId)?.let { current ->
                !current.isPurged && current.updatedAt == dto.updatedAt
            } == true }
            .flatMap { it.attachments }
            .filter { db.attachmentDao().getBySyncId(it.syncId)?.let { row -> db.noteDao().getById(row.noteId)?.syncId == it.noteSyncId } == true }
            .filter { !hasExpectedAttachment(it) }
        ApplyStats(appliedNotes, appliedNotebooks, conflicts, missing)
    }

    /** Finish the current entity transaction; cancellation is checked before the next one. */
    private suspend fun syncTransaction(block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { db.withTransaction { block() } }
    }

    private suspend fun applyNote(dto: NoteDto, existing: NoteEntity?, discardedFiles: MutableList<String>, localConflict: Boolean = false) {
        val rawEntity = NoteEntity(
            id = existing?.id ?: 0,
            syncId = dto.syncId,
            title = dto.title,
            content = dto.content,
            textFormat = dto.textFormat,
            tablesData = dto.tablesData ?: existing?.tablesData.orEmpty(),
            notebookId = dto.notebookSyncId?.let { db.notebookDao().getBySyncId(it)?.id },
            isFavorite = dto.favorite,
            isDeleted = dto.deleted,
            isPinned = dto.pinned ?: existing?.isPinned ?: false,
            isPurged = dto.purged == true,
            noteColor = dto.noteColor ?: existing?.noteColor.orEmpty(),
            createdAt = dto.createdAt,
            reminderAt = dto.reminderAt,
            taskDueAt = if (dto.taskDeadline != null) dto.taskDeadline.at.also { require(com.notessync.app.ui.tasks.TaskDeadlines.valid(it)) { "Invalid task deadline" } } else existing?.taskDueAt,
            dayKey = dto.dayKey,
            isTask = dto.isTask,
            isCompleted = dto.isCompleted,
            taskColor = dto.taskColor,
            taskPriority = dto.taskPriority.takeIf { it in 0..3 } ?: 0,
            taskElapsedMillis = dto.taskElapsedMillis,
            taskTimerStartedAt = dto.taskTimerStartedAt,
            taskCountdownMillis = dto.taskCountdownMillis,
            taskCountdownEndAt = dto.taskCountdownEndAt,
            taskCompletedAt = dto.taskCompletedAt,
            isProtected = dto.isProtected,
            passwordHash = dto.passwordHash,
            passwordSalt = dto.passwordSalt,
            updatedAt = dto.updatedAt,
            dirty = localConflict
        )
        val entity = when {
            dto.purged == true -> NoteActions.tombstone(rawEntity, dto.updatedAt).copy(dirty = false)
            dto.deleted -> NoteActions.paused(rawEntity, System.currentTimeMillis())
            else -> rawEntity
        }
        val localId = if (existing == null) {
            db.noteDao().upsert(entity)
        } else {
            db.noteDao().update(entity)
            existing.id
        }
        ProtectionSession.lockTask(dto.syncId)

        val previousAttachments = db.attachmentDao().getByNoteId(localId).associateBy { it.syncId }
        db.attachmentDao().deleteByNoteId(localId)
        val retained = if (entity.isPurged) emptySet() else dto.attachments.map { it.syncId }.toSet()
        discardedFiles.addAll(previousAttachments.filterKeys { it !in retained }.values.map { it.filePath })
        for (meta in if (entity.isPurged) emptyList() else dto.attachments) {
            // Вложения с небезопасным идентификатором пропускаем (см. attachmentFile).
            if (!SAFE_ATTACHMENT_ID.matches(meta.syncId)) continue
            val old = previousAttachments[meta.syncId]
            val size = PhotoDisplaySize(meta.displayWidthDp ?: old?.displayWidthDp ?: PhotoDisplaySize.DEFAULT_WIDTH,
                meta.displayHeightDp ?: old?.displayHeightDp ?: PhotoDisplaySize.DEFAULT_HEIGHT).bounded()
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id = old?.id ?: 0,
                    syncId = meta.syncId,
                    noteId = localId,
                    filePath = old?.filePath ?: attachmentFile(meta.syncId).absolutePath,
                    mimeType = meta.mimeType,
                    sizeBytes = meta.sizeBytes,
                    createdAt = old?.createdAt ?: System.currentTimeMillis(),
                    displayWidthDp = size.width, displayHeightDp = size.height,
                    previewRevision = old?.previewRevision ?: 0
                )
            )
        }

        db.tagDao().detachAllFromNote(localId)
        if (entity.isDeleted || entity.isCompleted) {
            ReminderScheduler.cancel(context, localId)
            ReminderScheduler.cancelTimer(context, localId)
        }
        for (name in if (entity.isPurged) emptyList() else dto.tagNames) {
            val tag = db.tagDao().getByName(name) ?: TagEntity(name = name, dirty = false)
                .also { db.tagDao().insert(it) }
            db.tagDao().attachToNote(NoteTagCrossRef(noteId = localId, tagId = tag.id))
        }
    }

    private suspend fun preserveConflictFiles(original: NoteDto, copy: NoteDto, files: List<AttachmentEntity>, discarded: MutableList<String>) {
        val sources = files.associateBy { it.syncId }
        for ((old, fresh) in original.attachments.zip(copy.attachments)) {
            val file = File(sources.getValue(old.syncId).filePath)
            val target = attachmentFile(fresh.syncId)
            discarded.add(target.absolutePath) // On rollback remove new files; committed references keep them.
            val size = file.inputStream().use { PhotoFileWriter(SyncLimits.ATTACHMENT_BYTES).write(it, target) }
            require(size == fresh.sizeBytes && target.inputStream().use { Hashing.sha256Hex(it, SyncLimits.ATTACHMENT_BYTES) } == fresh.sha256) { "Conflict attachment changed" }
            db.attachmentDao().updateFile(fresh.syncId, target.absolutePath, size)
        }
    }

    override suspend fun hasAttachmentBytes(attachmentSyncId: String): Boolean = withContext(Dispatchers.IO) {
        if (!SAFE_ATTACHMENT_ID.matches(attachmentSyncId)) return@withContext true
        val file = db.attachmentDao().getBySyncId(attachmentSyncId)?.let { File(it.filePath) } ?: attachmentFile(attachmentSyncId)
        file.isFile && file.length() > 0
    }

    /** Existing bytes with the same ID can be stale; compare the incoming size and digest. */
    private suspend fun hasExpectedAttachment(meta: AttachmentDto): Boolean = withContext(Dispatchers.IO) {
        val row = db.attachmentDao().getBySyncId(meta.syncId) ?: return@withContext false
        val file = File(row.filePath)
        file.isFile && file.length() == meta.sizeBytes &&
            attachmentDigest(file) == meta.sha256
    }

    override suspend fun readAttachmentBytes(attachmentSyncId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!SAFE_ATTACHMENT_ID.matches(attachmentSyncId)) return@withContext null
        val file = db.attachmentDao().getBySyncId(attachmentSyncId)?.let { File(it.filePath) } ?: attachmentFile(attachmentSyncId)
        if (!file.isFile) return@withContext null
        require(file.length() <= SyncLimits.ATTACHMENT_BYTES) { "Attachment too large" }
        // A growing file is bounded too; length() alone is not an allocation limit.
        file.inputStream().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size().toLong() + count <= SyncLimits.ATTACHMENT_BYTES) { "Attachment too large" }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }

    override suspend fun openAttachment(attachmentSyncId: String): SyncAttachmentSource? = withContext(Dispatchers.IO) {
        if (!SAFE_ATTACHMENT_ID.matches(attachmentSyncId)) return@withContext null
        val file = db.attachmentDao().getBySyncId(attachmentSyncId)?.let { File(it.filePath) } ?: attachmentFile(attachmentSyncId)
        if (!file.isFile) return@withContext null
        val input = FileInputStream(file)
        try {
            val size = input.channel.size()
            require(size <= SyncLimits.ATTACHMENT_BYTES) { "Attachment too large" }
            SyncAttachmentSource(size, input)
        } catch (error: Throwable) { input.close(); throw error }
    }

    override suspend fun receiveAttachment(meta: AttachmentDto): SyncAttachmentSink = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        SyncLimits.attachment(meta)
        check(incomingDir.isDirectory || incomingDir.mkdirs()) { "Cannot prepare sync file" }
        if (sweptIncoming.compareAndSet(false, true)) {
            val cutoff = System.currentTimeMillis() - 30L * 60 * 1000
            incomingDir.listFiles()?.forEach { if (it.name.startsWith("receive-") && it.name.endsWith(".tmp") && it.lastModified() < cutoff) it.delete() }
        }
        val pending = File.createTempFile("receive-", ".tmp", incomingDir)
        val raw = try { FileOutputStream(pending) } catch (error: Throwable) { pending.delete(); throw error }
        val output = BufferedOutputStream(raw, 64 * 1024)
        VerifiedAttachmentSink(meta, output, publish = {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO + NonCancellable) {
                raw.fd.sync(); output.close()
                db.withTransaction {
                    val parent = db.noteDao().getBySyncId(meta.noteSyncId) ?: return@withTransaction
                    if (parent.isPurged) return@withTransaction
                    val row = db.attachmentDao().getBySyncId(meta.syncId)
                    if (row != null && row.noteId != parent.id) return@withTransaction
                    val file = row?.let { File(it.filePath) } ?: attachmentFile(meta.syncId)
                    check(pending.renameTo(file)) { "Cannot publish sync file" }
                    db.attachmentDao().updateFile(meta.syncId, file.absolutePath, meta.sizeBytes)
                }
            }
        }, cleanup = { pending.delete() })
    }

    override suspend fun writeAttachmentBytes(meta: AttachmentDto, data: ByteArray) = withContext(Dispatchers.IO + NonCancellable) {
        SyncLimits.attachment(meta)
        val parent = db.noteDao().getBySyncId(meta.noteSyncId) ?: return@withContext
        if (parent.isPurged) return@withContext
        receiveAttachment(meta).use { sink -> sink.output.write(data); sink.commit() }
    }

    override suspend fun markSynced(changeSet: ChangeSet) = markAcknowledged(com.notessync.app.sync.core.SyncAcknowledgements(
        changeSet.notes.map { com.notessync.app.sync.core.SyncVersion(it.syncId, it.updatedAt) },
        changeSet.notebooks.map { com.notessync.app.sync.core.SyncVersion(it.syncId, it.updatedAt) }))

    override suspend fun markAcknowledged(versions: com.notessync.app.sync.core.SyncAcknowledgements) {
        for (group in versions.notes.chunked(128)) {
            currentCoroutineContext().ensureActive()
            db.withTransaction { group.forEach { db.noteDao().markVersionSynced(it.syncId, it.updatedAt) } }
        }
        for (group in versions.notebooks.chunked(128)) {
            currentCoroutineContext().ensureActive()
            db.withTransaction { group.forEach { db.notebookDao().markVersionSynced(it.syncId, it.updatedAt) } }
        }
    }

    private fun attachmentFile(syncId: String): File {
        // Аудит 2026-11-11: syncId приходит от пира и становится именем файла.
        // Без проверки «../../../…» позволял бы писать вне каталога вложений.
        require(SAFE_ATTACHMENT_ID.matches(syncId)) { uiText("Некорректный syncId вложения") }
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
        textFormat = textFormat,
        tablesData = tablesData,
        favorite = isFavorite,
        pinned = isPinned, purged = isPurged, noteColor = noteColor,
        deleted = isDeleted,
        createdAt = createdAt,
        updatedAt = updatedAt,
        notebookSyncId = notebookSyncId,
        tagNames = tagNames,
        attachments = attachments,
        reminderAt = reminderAt,
        taskDeadline = com.notessync.app.sync.protocol.TaskDeadlineDto(taskDueAt),
        dayKey = dayKey,
        isTask = isTask,
        isCompleted = isCompleted,
        taskColor = taskColor,
        taskPriority = taskPriority,
        taskElapsedMillis = taskElapsedMillis,
        taskTimerStartedAt = taskTimerStartedAt,
        taskCountdownMillis = taskCountdownMillis,
        taskCountdownEndAt = taskCountdownEndAt,
        taskCompletedAt = taskCompletedAt,
        isProtected = isProtected,
        passwordHash = passwordHash,
        passwordSalt = passwordSalt
    )

    private suspend fun AttachmentEntity.toDto(noteSyncId: String): AttachmentDto? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.isFile) return@withContext null
        require(file.length() <= SyncLimits.ATTACHMENT_BYTES) { "Attachment too large" }
        AttachmentDto(
            syncId = syncId,
            noteSyncId = noteSyncId,
            mimeType = mimeType,
            sizeBytes = file.length(),
            sha256 = attachmentDigest(file),
            displayWidthDp = displayWidthDp, displayHeightDp = displayHeightDp
        )
    }

    companion object {
        // UUID вида 8-4-4-4-12: единственная допустимая форма syncId вложения.
        private val SAFE_ATTACHMENT_ID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}
