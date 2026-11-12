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
    indices = [Index("notebookId"), Index("updatedAt"), Index("syncId"), Index("dayKey")]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Global sync identity; local database ids can differ between devices.
    val syncId: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    @ColumnInfo(defaultValue = "''") val textFormat: String = "",
    val notebookId: Long? = null,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val reminderAt: Long? = null,
    val dayKey: Long? = null,
    val isTask: Boolean = false,
    val isCompleted: Boolean = false,
    val taskColor: String = "violet",
    @ColumnInfo(defaultValue = "0") val taskPriority: Int = 0,
    val taskElapsedMillis: Long = 0L,
    val taskTimerStartedAt: Long? = null,
    // Таймер обратного отсчёта (Этап 30): остаток на паузе и дедлайн при работе.
    val taskCountdownMillis: Long = 0L,
    val taskCountdownEndAt: Long? = null,
    // Когда задача была выполнена (Этап 31): основа аналитики. Null у старых записей —
    // для них статистика использует updatedAt.
    val taskCompletedAt: Long? = null,
    val isProtected: Boolean = false,
    val passwordHash: String? = null,
    val passwordSalt: String? = null,
    // Sync metadata: updatedAt drives deltas and dirty marks local changes.
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true
)
