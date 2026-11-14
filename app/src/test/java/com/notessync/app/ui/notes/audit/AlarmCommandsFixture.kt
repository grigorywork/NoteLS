package com.notessync.app.ui.notes.audit

import android.content.Context
import com.notessync.app.reminders.*

/** Deterministic port for component-generation tests; production binding is covered by Native Room tests. */
internal class ShadowAlarmCommands(private val app: Context) : NoteAlarmCommands {
    override suspend fun schedule(id: Long, syncId: String, at: Long, timer: Boolean, current: () -> Boolean): AlarmCommandResult {
        if (!current()) return AlarmCommandResult.STALE
        val set = if (timer) ReminderScheduler.scheduleTimer(app, id, "Fixture", at, syncId)
            else ReminderScheduler.schedule(app, id, "Fixture", at, syncId)
        return if (set) AlarmCommandResult.APPLIED else AlarmCommandResult.UNAVAILABLE
    }
    override suspend fun cancel(id: Long, syncId: String, timer: Boolean, current: () -> Boolean) {
        if (current()) { if (timer) ReminderScheduler.cancelTimer(app, id) else ReminderScheduler.cancel(app, id) }
    }
}
internal class GatedAlarmCommands(private val delegate: NoteAlarmCommands,
    private val before: suspend () -> Unit) : NoteAlarmCommands {
    override suspend fun schedule(id: Long, syncId: String, at: Long, timer: Boolean, current: () -> Boolean): AlarmCommandResult {
        before()
        return delegate.schedule(id, syncId, at, timer, current)
    }
    override suspend fun cancel(id: Long, syncId: String, timer: Boolean, current: () -> Boolean) =
        delegate.cancel(id, syncId, timer, current)
}
