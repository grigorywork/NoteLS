package com.notessync.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notessync.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking

/** После перезагрузки устройства будильники сбрасываются — ставим заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            runBlocking {
                val now = System.currentTimeMillis()
                val database = AppDatabase.get(appContext)
                database.noteDao().getWithFutureReminders(now).forEach { note ->
                    val notebookProtected = note.notebookId?.let {
                        database.notebookDao().getById(it)?.isProtected
                    } == true
                    val title = if (note.isProtected || notebookProtected) {
                        "Защищённая запись"
                    } else {
                        note.title.ifBlank { "Без названия" }
                    }
                    ReminderScheduler.schedule(appContext, note.id, title, note.reminderAt!!)
                }
            }
            pending.finish()
        }.start()
    }
}
