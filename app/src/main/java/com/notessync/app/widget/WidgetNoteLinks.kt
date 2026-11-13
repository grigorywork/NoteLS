package com.notessync.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.notessync.app.MainActivity
import com.notessync.app.data.local.AppDatabase

data class WidgetNoteRequest(val noteId: Long, val syncId: String, val sequence: Long)

object WidgetNoteLinks {
    private const val ACTION_OPEN = "com.notessync.app.widget.OPEN_NOTE"
    private const val NOTE_ID = "widgetNoteId"
    private const val SYNC_ID = "widgetNoteSyncId"
    fun intent(context: Context, widgetId: Int, id: Long, syncId: String) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN).setData(Uri.parse("notels://compact/$widgetId/note/$id/${Uri.encode(syncId)}"))
        .putExtra(NOTE_ID, id).putExtra(SYNC_ID, syncId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun read(intent: Intent, sequence: Long): WidgetNoteRequest? {
        if (intent.action != ACTION_OPEN) return null
        val id = intent.getLongExtra(NOTE_ID, -1)
        val syncId = intent.getStringExtra(SYNC_ID) ?: return null
        return if (id > 0 && syncId.length in 1..160 && syncId.isNotBlank()) WidgetNoteRequest(id, syncId, sequence) else null
    }
    suspend fun canOpen(context: Context, request: WidgetNoteRequest): Boolean {
        val current = AppDatabase.get(context).noteDao().getAccess(request.noteId) ?: return false
        return current.syncId == request.syncId && !current.isDeleted && !current.isPurged && !current.isProtected && !current.notebookProtected
    }
}
