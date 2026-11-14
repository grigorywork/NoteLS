package com.notessync.app.ui.notes

/** SUBSTR counts Unicode codepoints: body <=4000 UTF16 units, clipped again at the visible2000-unit boundary. */
internal const val NOTE_CARD_COLUMNS = "notes.id, notes.syncId, SUBSTR(notes.title,1,256) AS title, " +
    "SUBSTR(notes.content,1,2000) AS content, SUBSTR(notes.textFormat,1,250001) AS textFormat, " +
    "(notes.tablesData != '') AS hasTables, notes.notebookId, notes.isFavorite, notes.isDeleted, notes.isPurged, " +
    "notes.isPinned, SUBSTR(notes.noteColor,1,16) AS noteColor, notes.taskDueAt, notes.isTask, notes.isCompleted, " +
    "SUBSTR(notes.taskColor,1,16) AS taskColor, notes.taskPriority, notes.taskElapsedMillis, notes.taskTimerStartedAt, " +
    "notes.taskCountdownMillis, notes.taskCountdownEndAt, notes.isProtected, notes.updatedAt"
