package com.notessync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    @Query("SELECT * FROM notebooks WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getById(id: Long): NotebookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: NotebookEntity): Long

    @Update
    suspend fun update(notebook: NotebookEntity)

    // Мягкое удаление, чтобы удаление синхронизировалось (Этап 5).
    @Query("UPDATE notebooks SET isDeleted = 1, updatedAt = :timestamp, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM notebooks WHERE dirty = 1 OR updatedAt > :since")
    suspend fun getChangedSince(since: Long): List<NotebookEntity>

    @Query("UPDATE notebooks SET dirty = 0 WHERE syncId IN (:syncIds)")
    suspend fun markSyncedBySyncId(syncIds: List<String>)

    @Query("SELECT * FROM notebooks WHERE syncId = ''")
    suspend fun getWithoutSyncId(): List<NotebookEntity>
}
