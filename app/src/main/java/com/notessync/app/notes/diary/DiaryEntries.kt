package com.notessync.app.notes.diary

import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NoteTagCrossRef
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.data.local.model.nextChangeTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A dated synced/backed-up note inside the freshly authorized private notebook. */
internal object DiaryEntries {
    const val TAG_NAME = "Дневник"
    suspend fun create(db: AppDatabase, expectedBook: com.notessync.app.data.local.entity.NotebookEntity, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): NoteEntity =
        db.withTransaction {
            val fresh = db.notebookDao().getById(expectedBook.id)
            check(fresh != null && fresh == expectedBook && DiaryPrivacy.valid(fresh) && com.notessync.app.security.ProtectionSession.isUnlocked(fresh))
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val tag = db.tagDao().getByName(TAG_NAME)
            val tagId = tag?.id ?: db.tagDao().insert(TagEntity(name = TAG_NAME, updatedAt = now)).takeIf { it > 0 }
                ?: requireNotNull(db.tagDao().getByName(TAG_NAME)).id
            if (tag?.isDeleted == true) db.tagDao().revive(tagId, nextChangeTimestamp(tag.updatedAt, now))
            val note = NoteEntity(title = date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)),
                notebookId = fresh.id, dayKey = date.toEpochDay(), createdAt = now, updatedAt = now)
            val id = db.noteDao().upsert(note)
            db.tagDao().attachToNote(NoteTagCrossRef(id, tagId))
            note.copy(id = id)
        }
}
