package com.notessync.app.data.backup

import com.notessync.app.settings.BackupFrequency
import org.junit.Assert.*
import org.junit.Test

class BackupPolicyTest {
    @Test fun `manual never creates automatic work`() {
        assertFalse(BackupPolicy.isDue(BackupFrequency.MANUAL, true, 0, Long.MAX_VALUE))
    }
    @Test fun `after-note requires a changed note rather than activity backgrounding`() {
        assertFalse(BackupPolicy.isDue(BackupFrequency.AFTER_NOTE, false, 0, 100))
        assertTrue(BackupPolicy.isDue(BackupFrequency.AFTER_NOTE, true, 100, 100))
    }
    @Test fun `interval uses last automatic copy and waits until boundary`() {
        val day = BackupFrequency.DAILY.intervalMillis
        assertFalse(BackupPolicy.isDue(BackupFrequency.DAILY, true, 1000, 1000 + day - 1))
        assertTrue(BackupPolicy.isDue(BackupFrequency.DAILY, false, 1000, 1000 + day))
    }
    @Test fun `first use is due without polling and clock rollback does not create a loop`() {
        assertTrue(BackupPolicy.isDue(BackupFrequency.EVERY_10_DAYS, false, 0, 1000))
        assertFalse(BackupPolicy.isDue(BackupFrequency.DAILY, true, 2000, 1000))
    }
}
