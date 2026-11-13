package com.notessync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notessync.app.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE noteId IN (:noteIds)")
    suspend fun getForSyncNotes(noteIds: List<Long>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE noteId = :noteId")
    suspend fun getByNoteId(noteId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE noteId = :noteId")
    fun observeByNoteId(noteId: Long): Flow<List<AttachmentEntity>>

    // Metadata only; the notes query controls visibility. Watching notes would rerun this on every text autosave.
    @Query("SELECT * FROM attachments WHERE id IN (SELECT MIN(id) FROM attachments WHERE mimeType LIKE 'image/%' GROUP BY noteId)")
    fun observeFirstPhotos(): Flow<List<AttachmentEntity>>

    @Query("SELECT COUNT(*) FROM attachments WHERE noteId = :noteId")
    suspend fun countByNoteId(noteId: Long): Int

    @Query("SELECT * FROM attachments WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity): Long

    @Query("UPDATE attachments SET filePath = :filePath, sizeBytes = :sizeBytes, previewRevision = previewRevision + 1 WHERE syncId = :syncId")
    suspend fun updateFile(syncId: String, filePath: String, sizeBytes: Long)

    @Query("UPDATE attachments SET displayWidthDp = :width, displayHeightDp = :height WHERE id = :id AND noteId = :noteId")
    suspend fun updateDisplaySize(id: Long, noteId: Long, width: Int, height: Int): Int

    // Older schema1 attachments received an empty ID during migration1→2.
    @Query("SELECT id FROM attachments WHERE syncId = '' LIMIT 128")
    suspend fun idsWithoutSyncId(): List<Long>

    @Query("UPDATE attachments SET syncId = :syncId WHERE id = :id AND syncId = ''")
    suspend fun assignSyncId(id: Long, syncId: String): Int

    @Query("UPDATE notes SET dirty = 1, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END WHERE id IN (SELECT noteId FROM attachments WHERE id = :attachmentId) AND isPurged = 0")
    suspend fun markParentIdentityChanged(attachmentId: Long, timestamp: Long)

    @Query("SELECT filePath FROM attachments")
    suspend fun getFilePaths(): List<String>

    @Query("DELETE FROM attachments WHERE noteId = :noteId")
    suspend fun deleteByNoteId(noteId: Long)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: Long)
}
