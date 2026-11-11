package com.notessync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true), Index("syncId")]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val name: String,
    val isDeleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true
)
