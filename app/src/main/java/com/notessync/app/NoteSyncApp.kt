package com.notessync.app

import android.app.Application
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.reminders.createReminderChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NoteSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createReminderChannel()
        // Этап 39: автобэкап по расписанию — проверка при каждом запуске,
        // сработает только если вышел интервал из настроек (или срочно после
        // первого запуска, когда бэкапов ещё не было).
        CoroutineScope(Dispatchers.IO).launch {
            BackupManager.autoBackupIfDue(this@NoteSyncApp, afterNote = false)
        }
    }
}
