package com.notessync.app.widget

import android.content.Context
import com.notessync.app.reminders.*

interface QuickTaskReminders {
    fun canNotify(): Boolean
    suspend fun schedule(id: Long, title: String, at: Long, syncId: String): Boolean
    suspend fun cancel(id: Long, syncId: String)
}

internal class AndroidQuickTaskReminders(private val app: Context) : QuickTaskReminders {
    private val commands = RoomNoteAlarmCommands(app)
    override fun canNotify() = ReminderScheduler.canNotify(app)
    override suspend fun schedule(id: Long, title: String, at: Long, syncId: String) =
        commands.schedule(id, syncId, at, false) { true } != AlarmCommandResult.UNAVAILABLE
    override suspend fun cancel(id: Long, syncId: String) {
        commands.cancel(id, syncId, false) { true }
        commands.cancel(id, syncId, true) { true }
    }
}
