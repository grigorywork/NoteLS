package com.notessync.app.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LauncherIconsTest {
    private class Components : LauncherComponents {
        val active = linkedSetOf(LauncherIcon.GLASS)
        var failOnce: LauncherIcon? = null
        val history = mutableListOf<Set<LauncherIcon>>()
        override fun enabled(icon: LauncherIcon) = icon in active
        override fun set(icon: LauncherIcon, enabled: Boolean) {
            if (failOnce == icon && !enabled) { failOnce = null; error("Package manager unavailable") }
            if (enabled) active.add(icon) else active.remove(icon)
            history.add(active.toSet())
        }
    }
    @Test fun `legacy switches never remove the last launch entry and reconstruct from platform state`() {
        val platform = Components()
        for (target in listOf(LauncherIcon.PAPER, LauncherIcon.FROSTED, LauncherIcon.GLASS)) {
            assertEquals(target, LauncherIcons(platform).select(target))
            assertEquals(setOf(target), platform.active)
            assertEquals(target, LauncherIcons(platform).current())
        }
        assertTrue(platform.history.all { it.isNotEmpty() })
    }
    @Test fun `failed legacy disable restores previous icon and retains a launch entry`() {
        val platform = Components().apply { failOnce = LauncherIcon.GLASS }
        assertThrows(IllegalStateException::class.java) { LauncherIcons(platform).select(LauncherIcon.PAPER) }
        assertEquals(setOf(LauncherIcon.GLASS), platform.active)
        assertTrue(platform.history.all { it.isNotEmpty() })
    }
    @Test fun `empty and duplicate interrupted states recover to exactly one launch entry`() {
        val platform = Components().apply { active.clear() }
        assertEquals(LauncherIcon.GLASS, LauncherIcons(platform).current())
        platform.active.add(LauncherIcon.FROSTED)
        assertEquals(LauncherIcon.GLASS, LauncherIcons(platform).current())
        assertEquals(setOf(LauncherIcon.GLASS), platform.active)
    }
    @Test fun `selecting current icon makes no package changes`() {
        val platform = Components()
        assertEquals(LauncherIcon.GLASS, LauncherIcons(platform).select(LauncherIcon.GLASS))
        assertTrue(platform.history.isEmpty())
    }
    @Test fun `atomic platform path selects once without legacy mutations`() {
        var selected = LauncherIcon.GLASS
        var calls = 0
        val platform = object : LauncherComponents {
            override fun enabled(icon: LauncherIcon) = icon == selected
            override fun set(icon: LauncherIcon, enabled: Boolean) = error("Must use atomic API")
            override fun atomicSelect(icon: LauncherIcon): Boolean { calls++; selected = icon; return true }
        }
        assertEquals(LauncherIcon.PAPER, LauncherIcons(platform).select(LauncherIcon.PAPER))
        assertEquals(1, calls)
    }
    @Test fun `real manifest aliases switch while main activity and explicit widget intents stay enabled`() {
        verifyManifestSwitch()
    }
    @Test @Config(sdk = [33]) fun `Android 13 atomic manifest switch preserves one launcher and enabled main activity`() {
        verifyManifestSwitch()
    }
    private fun verifyManifestSwitch() {
        val app: Application = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val main = ComponentName(app.packageName, "${app.packageName}.MainActivity")
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val icons = LauncherIcons(app)
        try {
            for (target in LauncherIcon.entries) {
                icons.select(target)
                val entries = pm.queryIntentActivities(query, 0)
                assertEquals(1, entries.size)
                assertEquals("${app.packageName}.launcher.${target.alias}", entries.single().activityInfo.name)
                assertEquals(target, LauncherIcons(app).current())
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(main))
                assertNotNull(pm.resolveActivity(Intent().setComponent(main), 0))
                assertTrue(pm.getActivityInfo(main, 0).enabled)
            }
        } finally { icons.select(LauncherIcon.GLASS) }
    }
}
