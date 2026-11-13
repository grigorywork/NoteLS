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

    @Query("SELECT id, SUBSTR(name, 1, 256) AS name, isProtected, isDeleted FROM notebooks ORDER BY name COLLATE NOCASE, id")
    fun observeForAnalytics(): Flow<List<com.notessync.app.data.local.model.AnalyticsNotebook>>

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

    @Query("UPDATE notebooks SET dirty = 0 WHERE syncId = :syncId AND updatedAt = :timestamp AND dirty = 1")
    suspend fun markVersionSynced(syncId: String, timestamp: Long)

    @Query("SELECT id FROM notebooks WHERE syncId = '' LIMIT 128")
    suspend fun idsWithoutSyncId(): List<Long>

    @Query("UPDATE notebooks SET syncId = :syncId, dirty = 1, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END WHERE id = :id AND syncId = ''")
    suspend fun assignSyncId(id: Long, syncId: String, timestamp: Long): Int
}
