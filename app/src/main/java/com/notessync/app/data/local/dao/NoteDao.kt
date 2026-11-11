package com.notessync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    // Фильтры (блокнот/избранное/тег) + сортировка собираются в один запрос (Этап 2).
    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class])
    fun rawQuery(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    // Мягкое удаление: запись помечается, чтобы удаление могло синхронизироваться (Этап 5).
    @Query("UPDATE notes SET isDeleted = 1, updatedAt = :timestamp, dirty = 1 WHERE id = :id")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    // Отмена удаления (Snackbar «Отменить», Этап 1).
    @Query("UPDATE notes SET isDeleted = 0, updatedAt = :timestamp, dirty = 1 WHERE id = :id")
    suspend fun restore(id: Long, timestamp: Long = System.currentTimeMillis())

    // Аудит 2026-11-11: вызывающий код обязан экранировать %, _ и \ в запросе
    // (см. NotesViewModel.escapeLike), иначе они работают как подстановочные.
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND " +
            "(title LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR content LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY updatedAt DESC"
    )
    fun search(query: String): Flow<List<NoteEntity>>

    // Изменённые с последней синхронизации — основа дельта-синхронизации (Этап 5).
    @Query("SELECT * FROM notes WHERE dirty = 1")
    suspend fun getDirty(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE dirty = 1 OR updatedAt > :since")
    suspend fun getChangedSince(since: Long): List<NoteEntity>

    @Query("UPDATE notes SET dirty = 0 WHERE syncId IN (:syncIds)")
    suspend fun markSyncedBySyncId(syncIds: List<String>)

    @Query("UPDATE notes SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    // Записи без syncId (например, после миграции со старой версии) получают UUID.
    @Query("SELECT * FROM notes WHERE syncId = ''")
    suspend fun getWithoutSyncId(): List<NoteEntity>

    // При удалении блокнота его заметки переходят в «Все заметки» (Этап 2).
    @Query("UPDATE notes SET notebookId = NULL, updatedAt = :timestamp, dirty = 1 WHERE notebookId = :notebookId")
    suspend fun clearNotebook(notebookId: Long, timestamp: Long = System.currentTimeMillis())

    // Будущие напоминания — для перепостановки после перезагрузки (Этап 3).
    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND reminderAt IS NOT NULL AND reminderAt > :now")
    suspend fun getWithFutureReminders(now: Long): List<NoteEntity>

    // Аналитика (Этап 31): все задачи без защищённых — статистика не раскрывает секреты.
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND isTask = 1 AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1)) " +
            "ORDER BY updatedAt DESC"
    )
    fun observeTasksForAnalytics(): Flow<List<NoteEntity>>

    // Заметки конкретного дня (Этап 8): календарь и быстрые заметки.
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND dayKey = :dayKey " +
            "AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1)) ORDER BY createdAt ASC"
    )
    fun observeByDay(dayKey: Long): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND dayKey IS NOT NULL " +
            "AND dayKey BETWEEN :fromDay AND :toDay AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1)) " +
            "ORDER BY isTask DESC, isCompleted ASC, createdAt ASC"
    )
    suspend fun getForDayRange(fromDay: Long, toDay: Long): List<NoteEntity>

    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND dayKey IS NOT NULL " +
            "AND dayKey BETWEEN :fromDay AND :toDay AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1)) " +
            "ORDER BY isTask DESC, isCompleted ASC, createdAt ASC"
    )
    fun observeForDayRange(fromDay: Long, toDay: Long): Flow<List<NoteEntity>>

    @Query(
        "SELECT DISTINCT dayKey FROM notes WHERE isDeleted = 0 AND dayKey IS NOT NULL " +
            "AND dayKey BETWEEN :fromDay AND :toDay AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1))"
    )
    suspend fun getDaysWithNotes(fromDay: Long, toDay: Long): List<Long>
}
