package com.notessync.app.data.subtasks

import androidx.sqlite.db.SupportSQLiteDatabase
import com.notessync.app.notes.subtasks.Subtask
import com.notessync.app.notes.subtasks.Subtasks

/** Validate the staged migrated database before live restore; at most100 rows retained at a time. */
internal fun validateSubtaskSnapshot(db: SupportSQLiteDatabase, cancelled: () -> Unit) {
    db.query("SELECT COUNT(*) FROM note_subtasks s JOIN notes n ON n.id = s.noteId WHERE n.isPurged = 1 OR ((n.isDeleted = 1 OR (n.isTask = 1 AND n.isCompleted = 1)) AND (s.startedAt IS NOT NULL OR s.countdownEndAt IS NOT NULL))").use {
        require(it.moveToFirst() && it.getLong(0) == 0L) { "Invalid closed parent subtasks" }
    }
    db.query("SELECT noteId,id,position,text,completed,elapsedMillis,startedAt,countdownMillis,countdownEndAt,countdownFinishedAt FROM note_subtasks ORDER BY noteId,position,id").use { c ->
        var parent = -1L; var total = 0; val rows = mutableListOf<Subtask>()
        while (c.moveToNext()) {
            cancelled(); require(++total <= 100_000) { "Subtask snapshot limit" }
            if (parent != c.getLong(0)) { Subtasks.checked(rows); rows.clear(); parent = c.getLong(0) }
            require(parent > 0 && c.getInt(2) == rows.size && c.getInt(4) in 0..1) { "Invalid subtask position" }
            rows.add(Subtask(c.getString(1), c.getString(3), c.getInt(4) == 1, c.getLong(5),
                if (c.isNull(6)) null else c.getLong(6), c.getLong(7), if (c.isNull(8)) null else c.getLong(8), if (c.isNull(9)) null else c.getLong(9)))
            require(rows.size <= Subtasks.MAX_ITEMS)
        }
        Subtasks.checked(rows)
    }
}
