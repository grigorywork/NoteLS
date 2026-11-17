package com.notessync.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("notebookId"), Index("updatedAt"), Index("syncId"), Index("dayKey"), Index(name = "index_notes_task_due", value = ["isDeleted", "isPurged", "isTask", "isCompleted", "taskDueAt", "isProtected", "notebookId"]), Index(name = "index_notes_visible_order", value = ["isDeleted", "isPurged", "isPinned", "updatedAt", "id", "isProtected", "notebookId"])]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    // Global sync identity; local database ids can differ between devices.
    override val syncId: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    @ColumnInfo(defaultValue = "''") val textFormat: String = "",
    @ColumnInfo(defaultValue = "''") val tablesData: String = "",
    override val notebookId: Long? = null,
    val isFavorite: Boolean = false,
    override val isDeleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") override val isPurged: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "''") override val noteColor: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val reminderAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") override val taskDueAt: Long? = null,
    val dayKey: Long? = null,
    override val isTask: Boolean = false,
    override val isCompleted: Boolean = false,
    override val taskColor: String = "violet",
    @ColumnInfo(defaultValue = "0") val taskPriority: Int = 0,
    val taskElapsedMillis: Long = 0L,
    override val taskTimerStartedAt: Long? = null,
    // Таймер обратного отсчёта (Этап 30): остаток на паузе и дедлайн при работе.
    val taskCountdownMillis: Long = 0L,
    override val taskCountdownEndAt: Long? = null,
    // Когда задача была выполнена (Этап 31): основа аналитики. Null у старых записей —
    // для них статистика использует updatedAt.
    val taskCompletedAt: Long? = null,
    override val isProtected: Boolean = false,
    val passwordHash: String? = null,
    val passwordSalt: String? = null,
    // Sync metadata: updatedAt drives deltas and dirty marks local changes.
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true
) : com.notessync.app.data.local.model.NoteListEntry
