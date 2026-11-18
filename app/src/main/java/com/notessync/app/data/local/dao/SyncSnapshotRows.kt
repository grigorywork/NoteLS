package com.notessync.app.data.local.dao

import androidx.room.Embedded
import com.notessync.app.data.local.entity.NoteEntity

/** Read projections only: no schema or persisted fields are added. */
data class SyncNoteRow(@Embedded val note: NoteEntity, val linkedNotebookSyncId: String?, val linkedNotebookDiary: Boolean = false)
data class SyncTagRow(val noteId: Long, val name: String)
