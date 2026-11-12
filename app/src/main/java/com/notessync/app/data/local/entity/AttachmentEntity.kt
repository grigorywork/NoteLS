package com.notessync.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("syncId")]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val noteId: Long,
    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "240") val displayWidthDp: Int = 240,
    @ColumnInfo(defaultValue = "180") val displayHeightDp: Int = 180,
    @ColumnInfo(defaultValue = "0") val previewRevision: Long = 0
)
