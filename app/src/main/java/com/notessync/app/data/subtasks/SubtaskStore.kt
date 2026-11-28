package com.notessync.app.data.subtasks

import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.SubtaskEntity
import com.notessync.app.notes.subtasks.Subtask
import com.notessync.app.notes.subtasks.Subtasks

/** Caller holds the parent identity/access transaction; update only changed rows, not every keystroke. */
internal suspend fun replaceSubtasks(db: AppDatabase, noteId: Long, rows: List<Subtask>) {
    Subtasks.checked(rows)
    val dao = db.subtaskDao(); val old = dao.get(noteId).associateBy { it.id }
    if (rows.size > old.size) require(dao.countAll() + rows.size - old.size <= 100_000) { "Subtask library limit" }
    val next = rows.mapIndexed { index, row -> SubtaskEntity.of(noteId, index, row) }
    val removed = old.keys - next.mapTo(hashSetOf()) { it.id }
    if (removed.isNotEmpty()) dao.delete(noteId, removed.toList())
    val changed = next.filter { old[it.id] != it }
    if (changed.isNotEmpty()) dao.put(changed)
}
