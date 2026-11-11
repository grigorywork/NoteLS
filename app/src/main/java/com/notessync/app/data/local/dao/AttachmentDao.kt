package com.notessync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notessync.app.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE noteId = :noteId")
    suspend fun getByNoteId(noteId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE noteId = :noteId")
    fun observeByNoteId(noteId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT COUNT(*) FROM attachments WHERE noteId = :noteId")
    suspend fun countByNoteId(noteId: Long): Int

    @Query("SELECT * FROM attachments WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity): Long

    @Query("UPDATE attachments SET filePath = :filePath, sizeBytes = :sizeBytes WHERE syncId = :syncId")
    suspend fun updateFile(syncId: String, filePath: String, sizeBytes: Long)

    @Query("DELETE FROM attachments WHERE noteId = :noteId")
    suspend fun deleteByNoteId(noteId: Long)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: Long)
}
