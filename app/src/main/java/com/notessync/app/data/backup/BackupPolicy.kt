package com.notessync.app.data.backup

import com.notessync.app.settings.BackupFrequency

internal object BackupPolicy {
    fun isDue(frequency: BackupFrequency, noteChanged: Boolean, lastAutoAt: Long, now: Long): Boolean =
        when (frequency) {
            BackupFrequency.MANUAL -> false
            BackupFrequency.AFTER_NOTE -> noteChanged
            else -> lastAutoAt <= 0 || (now >= lastAutoAt && now - lastAutoAt >= frequency.intervalMillis)
        }
}
