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
internal const val COMPACT_WIDGET_SQL = "SELECT id, SUBSTR(syncId, 1, 160) AS syncId, SUBSTR(title, 1, 256) AS title, SUBSTR(content, 1, 256) AS content, isTask, isCompleted, isPinned, SUBSTR(taskColor, 1, 16) AS taskColor, SUBSTR(noteColor, 1, 16) AS noteColor FROM notes WHERE id IN (SELECT id FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN (SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) ORDER BY isPinned DESC, updatedAt DESC, id DESC LIMIT 3) ORDER BY isPinned DESC, updatedAt DESC, id DESC"

internal const val WIDGET_DAY_SNIPPETS_SQL = "SELECT id, dayKey, SUBSTR(title, 1, 256) AS title, isTask, isCompleted, SUBSTR(taskColor, 1, 16) AS taskColor FROM notes WHERE id IN (SELECT id FROM notes INDEXED BY index_notes_dayKey WHERE dayKey = :day AND isDeleted = 0 AND isPurged = 0 AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN (SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) ORDER BY isTask DESC, isCompleted ASC, createdAt ASC, id ASC LIMIT 3) ORDER BY isTask DESC, isCompleted ASC, createdAt ASC, id ASC"

// The existing task_due index narrows to unfinished live tasks before bounded scalar reads.
internal const val RUNNING_TASK_SELECT = "SELECT notes.id, notes.syncId, CASE WHEN notes.isProtected = 1 OR COALESCE(notebooks.isProtected, 0) = 1 OR COALESCE(notebooks.isDiary, 0) = 1 THEN '' ELSE SUBSTR(notes.title, 1, 256) END AS title, (notes.isProtected = 1 OR COALESCE(notebooks.isProtected, 0) = 1 OR COALESCE(notebooks.isDiary, 0) = 1) AS hidden, notes.taskElapsedMillis, notes.taskTimerStartedAt, notes.taskCountdownMillis, notes.taskCountdownEndAt FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.isDeleted = 0 AND notes.isPurged = 0 AND notes.isTask = 1 AND notes.isCompleted = 0 AND COALESCE(notebooks.isDeleted, 0) = 0 AND LENGTH(notes.syncId) BETWEEN 1 AND 160 AND TRIM(notes.syncId) != '' "
internal const val RUNNING_TASKS_SQL = RUNNING_TASK_SELECT + "AND (notes.taskTimerStartedAt IS NOT NULL OR notes.taskCountdownEndAt > :now) ORDER BY notes.id LIMIT 20"

@Dao
interface NoteDao {

    @Query("SELECT id, syncId, isTask, isCompleted, taskElapsedMillis, taskTimerStartedAt, taskCountdownMillis, taskCountdownEndAt, taskCompletedAt, reminderAt FROM notes WHERE id = :id")
    fun observeTaskMetadata(id: Long): Flow<com.notessync.app.data.local.model.TaskMetadataSnapshot?>

    @Query("SELECT id, syncId, isTask, isCompleted, taskElapsedMillis, taskTimerStartedAt, taskCountdownMillis, taskCountdownEndAt, taskCompletedAt, reminderAt FROM notes WHERE id = :id")
    suspend fun getTaskMetadata(id: Long): com.notessync.app.data.local.model.TaskMetadataSnapshot?

    @Query(RUNNING_TASKS_SQL)
    fun observeRunningTasks(now: Long): Flow<List<com.notessync.app.tasks.running.RunningTaskEntry>>

    @Query(RUNNING_TASKS_SQL)
    suspend fun getRunningTasks(now: Long): List<com.notessync.app.tasks.running.RunningTaskEntry>

    @Query(RUNNING_TASK_SELECT + "AND notes.id = :id")
    suspend fun getRunningTask(id: Long): com.notessync.app.tasks.running.RunningTaskEntry?

    @Query("UPDATE notes SET taskElapsedMillis = :elapsed, taskTimerStartedAt = :started, taskCountdownMillis = :remaining, taskCountdownEndAt = :end, isCompleted = :completed, taskCompletedAt = :completedAt, reminderAt = CASE WHEN :completed THEN NULL ELSE reminderAt END, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :now) ELSE updatedAt END, dirty = 1 WHERE id = :id AND syncId = :syncId AND isTask = 1 AND isCompleted = 0 AND isDeleted = 0 AND isPurged = 0")
    suspend fun updateRunningTask(id: Long, syncId: String, elapsed: Long, started: Long?, remaining: Long, end: Long?, completed: Boolean, completedAt: Long?, now: Long): Int

    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class])
    fun diaryRows(query: SupportSQLiteQuery): Flow<List<com.notessync.app.notes.diary.DiaryRow>>

    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class, com.notessync.app.data.local.entity.TagEntity::class, com.notessync.app.data.local.entity.NoteTagCrossRef::class])
    fun diaryCandidates(query: SupportSQLiteQuery): Flow<List<com.notessync.app.notes.diary.DiaryCandidate>>

    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    // Фильтры (блокнот/избранное/тег) + сортировка собираются в один запрос (Этап 2).
    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class, com.notessync.app.data.local.entity.TagEntity::class, com.notessync.app.data.local.entity.NoteTagCrossRef::class])
    fun rawCards(query: SupportSQLiteQuery): Flow<List<com.notessync.app.data.local.model.NoteCardEntry>>

    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class])
    fun rawLockedTaskTargets(query: SupportSQLiteQuery): Flow<List<com.notessync.app.data.local.model.TaskUnlockTarget>>

    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class, com.notessync.app.data.local.entity.TagEntity::class, com.notessync.app.data.local.entity.NoteTagCrossRef::class])
    fun rawQuery(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    @RawQuery(observedEntities = [NoteEntity::class, NotebookEntity::class, com.notessync.app.data.local.entity.TagEntity::class, com.notessync.app.data.local.entity.NoteTagCrossRef::class])
    fun observeNextTaskDue(query: SupportSQLiteQuery): Flow<Long?>

    @Query("SELECT id, syncId, isTask, isDeleted, isPurged, isProtected, passwordHash, passwordSalt FROM notes WHERE id = :id")
    suspend fun getTaskProtection(id: Long): com.notessync.app.data.local.model.TaskProtectionSnapshot?

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

    @Query("UPDATE notes SET isProtected = :isProtected, passwordHash = :hash, passwordSalt = :salt, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END, dirty = 1 WHERE id = :id")
    suspend fun updateTaskProtection(id: Long, isProtected: Boolean, hash: String?, salt: String?, timestamp: Long)

    // Attachment changes affect sync metadata only; never rewrite a stale document snapshot.
    @Query("UPDATE notes SET dirty = 1, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END WHERE id = :id")
    suspend fun markContentChanged(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET reminderAt = NULL, dirty = 1, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END WHERE id = :id AND syncId = :syncId AND reminderAt = :expected AND isDeleted = 0")
    suspend fun clearFailedReminder(id: Long, expected: Long, syncId: String, timestamp: Long = System.currentTimeMillis()): Int

    // Мягкое удаление: запись помечается, чтобы удаление могло синхронизироваться (Этап 5).
    @Query("UPDATE notes SET isDeleted = 1, taskElapsedMillis = MAX(0, taskElapsedMillis) + CASE WHEN taskTimerStartedAt IS NULL THEN 0 ELSE MAX(0, :timestamp - taskTimerStartedAt) END, taskTimerStartedAt = NULL, taskCountdownMillis = CASE WHEN taskCountdownEndAt IS NULL THEN taskCountdownMillis ELSE MAX(0, taskCountdownEndAt - :timestamp) END, taskCountdownEndAt = NULL, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END, dirty = 1 WHERE id = :id AND isPurged = 0")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM notes WHERE isDeleted = 1 AND isPurged = 0 AND id > :afterId ORDER BY id LIMIT 400")
    suspend fun getTrashBatch(afterId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE isDeleted = 1 AND isPurged = 0")
    suspend fun getTrash(): List<NoteEntity>

    @Query("UPDATE notes SET dirty = 0 WHERE syncId = :syncId AND updatedAt = :timestamp AND dirty = 1")
    suspend fun markVersionSynced(syncId: String, timestamp: Long)

    // Отмена удаления (Snackbar «Отменить», Этап 1).
    @Query("UPDATE notes SET isDeleted = 0, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END, dirty = 1 WHERE id = :id AND isPurged = 0")
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

    @Query("SELECT notes.*, notebooks.syncId AS linkedNotebookSyncId, COALESCE(notebooks.isDiary, 0) AS linkedNotebookDiary FROM notes " +
        "LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.dirty = 1 OR notes.updatedAt > :since")
    suspend fun getSyncSnapshotSince(since: Long): List<SyncNoteRow>

    @Query("UPDATE notes SET dirty = 0 WHERE syncId IN (:syncIds)")
    suspend fun markSyncedBySyncId(syncIds: List<String>)

    @Query("UPDATE notes SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    // Записи без syncId (например, после миграции со старой версии) получают UUID.
    @Query("SELECT id FROM notes WHERE syncId = '' LIMIT 128")
    suspend fun idsWithoutSyncId(): List<Long>

    @Query("UPDATE notes SET syncId = :syncId, dirty = 1, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END WHERE id = :id AND syncId = ''")
    suspend fun assignSyncId(id: Long, syncId: String, timestamp: Long): Int

    // При удалении блокнота его заметки переходят в «Все заметки» (Этап 2).
    @Query("UPDATE notes SET notebookId = NULL, updatedAt = CASE WHEN updatedAt < 9223372036854775807 THEN MAX(updatedAt + 1, :timestamp) ELSE updatedAt END, dirty = 1 WHERE notebookId = :notebookId")
    suspend fun clearNotebook(notebookId: Long, timestamp: Long = System.currentTimeMillis())

    // Canonical effect of a received parent tombstone; the note's own version and dirty flag stay intact.
    @Query("UPDATE notes SET notebookId = NULL WHERE notebookId = :notebookId AND EXISTS (SELECT 1 FROM notebooks WHERE id = :notebookId AND isDeleted = 1)")
    suspend fun detachDeletedNotebook(notebookId: Long)

    @Query(com.notessync.app.data.local.model.NOTE_ALARM_SELECT + "WHERE n.id IN (:ids)")
    suspend fun getAlarmSnapshots(ids: List<Long>): List<com.notessync.app.data.local.model.NoteAlarmSnapshot>

    @Query(com.notessync.app.data.local.model.NOTE_ALARM_SELECT + "WHERE n.id = :id")
    suspend fun getAlarmSnapshot(id: Long): com.notessync.app.data.local.model.NoteAlarmSnapshot?

    @Query(com.notessync.app.data.local.model.NOTE_ALARM_SELECT + "WHERE n.id > :afterId AND n.isDeleted = 0 AND n.isPurged = 0 " +
        "AND (n.isTask = 0 OR n.isCompleted = 0) AND (n.reminderAt > :now OR (n.isTask = 1 AND n.taskCountdownEndAt > :now)) ORDER BY n.id LIMIT 200")
    suspend fun getAlarmPage(afterId: Long, now: Long): List<com.notessync.app.data.local.model.NoteAlarmSnapshot>

    // Будущие напоминания — для перепостановки после перезагрузки (Этап 3).
    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND reminderAt IS NOT NULL AND reminderAt > :now")
    suspend fun getWithFutureReminders(now: Long): List<NoteEntity>

    @Query("SELECT notes.id, notes.syncId, notes.isDeleted, notes.isPurged, notes.isProtected, notes.notebookId, COALESCE((notebooks.isProtected OR notebooks.isDiary), 0) AS notebookProtected, notes.updatedAt, COALESCE(notebooks.updatedAt, 0) AS notebookUpdatedAt FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.id = :id")
    suspend fun getTransformAccess(id: Long): com.notessync.app.data.local.model.TransformAccess?

    @Query("SELECT notes.id, notes.syncId, notes.isDeleted, notes.isPurged, notes.isProtected, notes.notebookId, COALESCE((notebooks.isProtected OR notebooks.isDiary), 0) AS notebookProtected, notes.updatedAt, COALESCE(notebooks.updatedAt, 0) AS notebookUpdatedAt FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.id = :id")
    fun observeTransformAccess(id: Long): Flow<com.notessync.app.data.local.model.TransformAccess?>

    @Query("SELECT notes.id, notes.syncId, notes.isDeleted, notes.isPurged, notes.isProtected, notes.notebookId, COALESCE((notebooks.isProtected OR notebooks.isDiary), 0) AS notebookProtected, COALESCE(notebooks.isDiary, 0) AS notebookDiary FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.id = :id")
    suspend fun getAccess(id: Long): com.notessync.app.data.local.model.NoteAccess?

    @Query("SELECT notes.id, notes.syncId, notes.isDeleted, notes.isPurged, notes.isProtected, notes.notebookId, COALESCE((notebooks.isProtected OR notebooks.isDiary), 0) AS notebookProtected, COALESCE(notebooks.isDiary, 0) AS notebookDiary FROM notes LEFT JOIN notebooks ON notebooks.id = notes.notebookId WHERE notes.id = :id")
    fun observeAccess(id: Long): Flow<com.notessync.app.data.local.model.NoteAccess?>

    @Query(COMPACT_WIDGET_SQL)
    fun observeCompactWidget(): Flow<List<com.notessync.app.data.local.model.CompactWidgetEntry>>

    @Query("SELECT dayKey, COUNT(*) AS total FROM notes INDEXED BY index_notes_dayKey WHERE dayKey BETWEEN :fromDay AND :toDay AND isDeleted = 0 AND isPurged = 0 AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN (SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) GROUP BY dayKey")
    suspend fun getWidgetDayCounts(fromDay: Long, toDay: Long): List<com.notessync.app.data.local.model.WidgetDayCount>

    @Query(WIDGET_DAY_SNIPPETS_SQL)
    suspend fun getWidgetDaySnippets(day: Long): List<com.notessync.app.data.local.model.WidgetDaySnippet>

    // Lightweight statistics, including ordinary notes. No bodies, tables, formatting or attachment paths.
    @Query("SELECT id, CASE WHEN isTask = 1 THEN SUBSTR(title, 1, 256) ELSE '' END AS title, notebookId, isTask, isCompleted, updatedAt, taskCompletedAt, taskElapsedMillis, taskColor, isDeleted, isPurged, isProtected, taskDueAt, syncId FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN (SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1))")
    fun observeForAnalytics(): Flow<List<com.notessync.app.data.local.model.AnalyticsEntry>>

    // Аналитика (Этап 31): все задачи без защищённых — статистика не раскрывает секреты.
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND isTask = 1 AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) " +
            "ORDER BY updatedAt DESC"
    )
    fun observeTasksForAnalytics(): Flow<List<NoteEntity>>

    // Заметки конкретного дня (Этап 8): календарь и быстрые заметки.
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND dayKey = :dayKey " +
            "AND isProtected = 0 AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) ORDER BY createdAt ASC"
    )
    fun observeByDay(dayKey: Long): Flow<List<NoteEntity>>

    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND dayKey IS NOT NULL " +
            "AND dayKey BETWEEN :fromDay AND :toDay AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) " +
            "ORDER BY isTask DESC, isCompleted ASC, createdAt ASC"
    )
    suspend fun getForDayRange(fromDay: Long, toDay: Long): List<NoteEntity>

    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND dayKey IS NOT NULL " +
            "AND dayKey BETWEEN :fromDay AND :toDay AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1)) " +
            "ORDER BY isTask DESC, isCompleted ASC, createdAt ASC"
    )
    fun observeForDayRange(fromDay: Long, toDay: Long): Flow<List<NoteEntity>>

    @Query(
        "SELECT DISTINCT dayKey FROM notes WHERE isDeleted = 0 AND isPurged = 0 AND dayKey IS NOT NULL " +
            "AND dayKey BETWEEN :fromDay AND :toDay AND isProtected = 0 " +
            "AND (notebookId IS NULL OR notebookId NOT IN " +
            "(SELECT id FROM notebooks WHERE isProtected = 1 OR isDiary = 1))"
    )
    suspend fun getDaysWithNotes(fromDay: Long, toDay: Long): List<Long>
}
