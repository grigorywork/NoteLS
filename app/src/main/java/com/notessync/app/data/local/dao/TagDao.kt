package com.notessync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    // Теги сопоставляются между устройствами по имени — человеку важно имя, а не id.
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachToNote(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM note_tag_cross_ref WHERE noteId = :noteId")
    suspend fun detachAllFromNote(noteId: Long)

    @Query("DELETE FROM note_tag_cross_ref WHERE noteId = :noteId AND tagId = :tagId")
    suspend fun detachFromNote(noteId: Long, tagId: Long)

    @Query(
        "SELECT tags.name FROM tags INNER JOIN note_tag_cross_ref " +
            "ON tags.id = note_tag_cross_ref.tagId WHERE note_tag_cross_ref.noteId = :noteId"
    )
    suspend fun getTagNamesForNote(noteId: Long): List<String>
}
