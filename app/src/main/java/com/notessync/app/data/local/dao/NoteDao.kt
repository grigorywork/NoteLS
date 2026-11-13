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

// Choose three identities using the covering metadata index before reading bounded title/body previews.
internal const val COMPACT_WIDGET_SQL = "SELECT id, SUBSTR(syncId, 1, 160) AS syncId, SUBSTR(title, 1, 256) AS title, SUBSTR(content, 1, 256) AS content, isTask, isCompleted, isPinned, SUBSTR(taskColor, 1, 16) AS taskColor, SUBSTR(noteColor, 1, 16) AS noteColor FROM notes WHERE id IN (SELECT id FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN (SELECT id FROM notebooks WHERE isProtected = 1)) ORDER BY isPinned DESC, updatedAt DESC, id DESC LIMIT 3) ORDER BY isPinned DESC, updatedAt DESC, id DESC"

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    // Фильтры (блокнот/избранное/тег) + сортировка собираются в один запрос (Этап 2).
    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class])
    fun rawQuery(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    // Attachment changes affect sync metadata only; never rewrite a stale document snapshot.
    @Query("UPDATE notes SET dirty = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun markContentChanged(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET reminderAt = NULL, dirty = 1, updatedAt = :timestamp WHERE id = :id AND reminderAt = :expected AND isDeleted = 0")
    suspend fun clearFailedReminder(id: Long, expected: Long, timestamp: Long = System.currentTimeMillis()): Int

    // Мягкое удаление: запись помечается, чтобы удаление могло синхронизироваться (Этап 5).
    @Query("UPDATE notes SET isDeleted = 1, taskElapsedMillis = MAX(0, taskElapsedMillis) + CASE WHEN taskTimerStartedAt IS NULL THEN 0 ELSE MAX(0, :timestamp - taskTimerStartedAt) END, taskTimerStartedAt = NULL, taskCountdownMillis = CASE WHEN taskCountdownEndAt IS NULL THEN taskCountdownMillis ELSE MAX(0, taskCountdownEndAt - :timestamp) END, taskCountdownEndAt = NULL, updatedAt = :timestamp, dirty = 1 WHERE id = :id AND isPurged = 0")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM notes WHERE isDeleted = 1 AND isPurged = 0 AND id > :afterId ORDER BY id LIMIT 400")
    suspend fun getTrashBatch(afterId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE isDeleted = 1 AND isPurged = 0")
    suspend fun getTrash(): List<NoteEntity>

    @Query("UPDATE notes SET dirty = 0 WHERE syncId = :syncId AND updatedAt = :timestamp")
    suspend fun markVersionSynced(syncId: String, timestamp: Long)

    // Отмена удаления (Snackbar «Отменить», Этап 1).
    @Query("UPDATE notes SET isDeleted = 0, updatedAt = :timestamp, dirty = 1 WHERE id = :id AND isPurged = 0")
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

    @Query("SELECT notes.id, notes.syncId, notes.isDeleted, notes.isPurged, notes.isProtected, notes.notebookId, COALESCE(notebooks.isProtected, 0) AS notebookProtected FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.id = :id")
    suspend fun getAccess(id: Long): com.notessync.app.data.local.model.NoteAccess?

    @Query("SELECT notes.id, notes.syncId, notes.isDeleted, notes.isPurged, notes.isProtected, notes.notebookId, COALESCE(notebooks.isProtected, 0) AS notebookProtected FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.id = :id")
    fun observeAccess(id: Long): Flow<com.notessync.app.data.local.model.NoteAccess?>

    @Query(COMPACT_WIDGET_SQL)
    fun observeCompactWidget(): Flow<List<com.notessync.app.data.local.model.CompactWidgetEntry>>

    // Lightweight statistics, including ordinary notes. No bodies, tables, formatting or attachment paths.
    @Query("SELECT id, CASE WHEN isTask = 1 THEN SUBSTR(title, 1, 256) ELSE '' END AS title, notebookId, isTask, isCompleted, updatedAt, taskCompletedAt, taskElapsedMillis, taskColor, isDeleted, isPurged, isProtected FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN (SELECT id FROM notebooks WHERE isProtected = 1))")
    fun observeForAnalytics(): Flow<List<com.notessync.app.data.local.model.AnalyticsEntry>>

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
