package com.notessync.app

import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.reminders.createReminderChannel

class NoteSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.notessync.app.settings.SettingsRepository.get(this)
        createReminderChannel()
        // Widget/notification broadcasts must not start a backup. Check only on
        // real activity sessions; leaving the app groups all editing into one copy.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) BackupManager.requestAutoBackup(this@NoteSyncApp, afterNote = false)
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) {
                    BackupManager.requestAutoBackup(this@NoteSyncApp, afterNote = true)
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
