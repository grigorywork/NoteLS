package com.notessync.app.settings

import android.app.Application
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TaskDurationPreferencesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun reload(): SettingsRepository {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        return SettingsRepository.get(app)
    }
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { reload().setAutomaticDurationUnits(true) }
    @Test fun `default enabled setting persists on reload without changing other preferences`() {
        val repository = reload(); assertTrue(repository.state.value.automaticDurationUnits)
        repository.setArchiveCompletedTasks(true); repository.setBackupFrequency(BackupFrequency.MANUAL)
        repository.setAutomaticDurationUnits(false)
        assertFalse(repository.state.value.automaticDurationUnits)
        val fresh = reload()
        assertFalse(fresh.state.value.automaticDurationUnits); assertTrue(fresh.state.value.archiveCompletedTasks)
        assertEquals(BackupFrequency.MANUAL, fresh.state.value.backupFrequency)
        fresh.setAutomaticDurationUnits(true); assertTrue(reload().state.value.automaticDurationUnits)
    }
    @Test fun `corrupted preference is safe and a new choice repairs only its key`() {
        prefs.edit().putInt("automatic_duration_units", 7).putString("unrelated", "keep").commit()
        val repository = reload(); assertTrue(repository.state.value.automaticDurationUnits)
        repository.setAutomaticDurationUnits(false)
        assertFalse(prefs.getBoolean("automatic_duration_units", true))
        assertEquals("keep", prefs.getString("unrelated", null)); assertFalse(reload().state.value.automaticDurationUnits)
    }
}
