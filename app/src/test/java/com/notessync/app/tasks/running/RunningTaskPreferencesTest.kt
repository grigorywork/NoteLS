package com.notessync.app.tasks.running

import android.app.Application
import android.content.Context
import com.notessync.app.settings.SettingsRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RunningTaskPreferencesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun reload(): SettingsRepository {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        return SettingsRepository.get(app)
    }
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { reload().setRunningTaskNotifications(true) }
    @Test fun `running notification switch persists separately from duration and completed-task preferences`() {
        val r = reload(); assertTrue(r.state.value.runningTaskNotifications)
        r.setAutomaticDurationUnits(false); r.setArchiveCompletedTasks(true); r.setRunningTaskNotifications(false)
        val fresh = reload(); assertFalse(fresh.state.value.runningTaskNotifications)
        assertFalse(fresh.state.value.automaticDurationUnits); assertTrue(fresh.state.value.archiveCompletedTasks)
        fresh.setRunningTaskNotifications(true); assertTrue(reload().state.value.runningTaskNotifications)
    }
    @Test fun `corrupt notification preference recovers safely and changing it retains unrelated settings`() {
        prefs.edit().putString("running_task_notifications", "corrupt").putString("unrelated", "keep").commit()
        val r = reload(); assertTrue(r.state.value.runningTaskNotifications)
        r.setRunningTaskNotifications(false); assertFalse(reload().state.value.runningTaskNotifications)
        assertEquals("keep", prefs.getString("unrelated", null))
    }
}
