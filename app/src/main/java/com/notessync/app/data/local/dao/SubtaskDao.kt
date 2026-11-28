package com.notessync.app.data.local.dao

import androidx.room.*
import com.notessync.app.data.local.entity.SubtaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubtaskDao {
    @Query("SELECT COUNT(*) FROM note_subtasks")
    suspend fun countAll(): Long
    @Query(com.notessync.app.data.subtasks.SUBTASK_RUNNING)
    fun observeRunning(now: Long): Flow<List<com.notessync.app.tasks.running.RunningTaskEntry>>
    @Query(com.notessync.app.data.subtasks.SUBTASK_RUNNING)
    suspend fun getRunning(now: Long): List<com.notessync.app.tasks.running.RunningTaskEntry>
    @Query(com.notessync.app.data.subtasks.SUBTASK_ENTRY + "AND n.id = :id AND s.id = :child")
    suspend fun getEntry(id: Long, child: String): com.notessync.app.tasks.running.RunningTaskEntry?
    @Query("SELECT MIN(s.countdownEndAt)" + com.notessync.app.data.subtasks.SUBTASK_FROM + "AND s.countdownEndAt > 0")
    suspend fun nextAlarm(): Long?
    @Query(com.notessync.app.data.subtasks.SUBTASK_ENTRY + "AND s.countdownEndAt > 0 AND s.countdownEndAt <= :now ORDER BY s.countdownEndAt,n.id,s.id LIMIT 100")
    suspend fun due(now: Long): List<com.notessync.app.tasks.running.RunningTaskEntry>
    @Query("UPDATE note_subtasks SET elapsedMillis = :elapsed, startedAt = :started, countdownMillis = :remaining, countdownEndAt = :end, completed = :completed, countdownFinishedAt = :finishedAt WHERE noteId = :id AND id = :child")
    suspend fun updateTiming(id: Long, child: String, elapsed: Long, started: Long?, remaining: Long, end: Long?, completed: Boolean, finishedAt: Long? = null): Int

    @Query("SELECT * FROM note_subtasks WHERE noteId = :id ORDER BY position, id")
    suspend fun get(id: Long): List<SubtaskEntity>
    @Query("SELECT * FROM note_subtasks WHERE noteId = :id ORDER BY position, id")
    fun observe(id: Long): Flow<List<SubtaskEntity>>
    @Query("SELECT * FROM note_subtasks WHERE noteId IN (:ids) ORDER BY noteId, position, id")
    suspend fun getForNotes(ids: List<Long>): List<SubtaskEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<SubtaskEntity>)
    @Query("DELETE FROM note_subtasks WHERE noteId = :id AND id IN (:removed)")
    suspend fun delete(id: Long, removed: List<String>)
    @Query("DELETE FROM note_subtasks WHERE noteId = :id")
    suspend fun clear(id: Long)
}
