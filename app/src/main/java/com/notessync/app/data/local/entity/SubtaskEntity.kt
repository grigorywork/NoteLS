package com.notessync.app.data.local.entity

import androidx.room.*
import com.notessync.app.notes.subtasks.Subtask

@Entity(tableName = "note_subtasks", primaryKeys = ["noteId", "id"],
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index("startedAt"), Index("countdownEndAt")])
data class SubtaskEntity(val noteId: Long, val id: String, val position: Int,
    val text: String, val completed: Boolean, val elapsedMillis: Long, val startedAt: Long?,
    val countdownMillis: Long, val countdownEndAt: Long?, val countdownFinishedAt: Long?) {
    fun item() = Subtask(id, text, completed, elapsedMillis, startedAt, countdownMillis, countdownEndAt, countdownFinishedAt)
    companion object {
        fun of(noteId: Long, position: Int, r: Subtask) = SubtaskEntity(noteId, r.id, position, r.text, r.completed,
            r.elapsedMillis, r.startedAt, r.countdownMillis, r.countdownEndAt, r.countdownFinishedAt)
    }
}
