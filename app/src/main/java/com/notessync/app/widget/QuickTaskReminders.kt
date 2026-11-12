package com.notessync.app.widget

import android.content.Context
import com.notessync.app.reminders.ReminderScheduler

interface QuickTaskReminders {
    fun canNotify(): Boolean
    fun schedule(id: Long, title: String, at: Long): Boolean
    fun cancel(id: Long)
}

internal class AndroidQuickTaskReminders(private val app: Context) : QuickTaskReminders {
    override fun canNotify() = ReminderScheduler.canNotify(app)
    override fun schedule(id: Long, title: String, at: Long) = ReminderScheduler.schedule(app, id, title, at)
    override fun cancel(id: Long) = ReminderScheduler.cancel(app, id)
}
