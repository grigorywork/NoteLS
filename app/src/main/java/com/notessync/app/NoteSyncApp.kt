package com.notessync.app

import android.app.Application
import com.notessync.app.reminders.createReminderChannel

class NoteSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createReminderChannel()
    }
}
