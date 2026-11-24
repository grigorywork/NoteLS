package com.notessync.app.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.notessync.app.MainActivity
import com.notessync.app.R
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlannerShortcutTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(ShortcutManager::class.java)
    @Before fun before() { SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN) }
    @Test fun `real platform pin uses stable ID supplied artwork and explicit main route independent of icon choice`() {
        shadowOf(manager).setIsRequestPinShortcutSupported(true)
        val platform = AndroidPlannerShortcut(app)
        assertTrue(platform.isSupported()); assertTrue(platform.request()); assertTrue(platform.request())
        val shortcut = manager.pinnedShortcuts.single()
        assertEquals(AndroidPlannerShortcut.ID, shortcut.id)
        assertEquals("Планировщик", shortcut.shortLabel.toString())
        assertEquals(MainActivity::class.java.name, shortcut.intent!!.component!!.className)
        assertTrue(PlannerShortcutLinks.matches(shortcut.intent!!))
        val icon = ShortcutInfo::class.java.getDeclaredMethod("getIcon").invoke(shortcut) as Icon
        assertEquals(Icon.TYPE_RESOURCE, icon.type); assertEquals(R.mipmap.ic_launcher, icon.resId)
        LauncherIcons(app).select(LauncherIcon.FROSTED)
        assertTrue(PlannerShortcutLinks.matches(manager.pinnedShortcuts.single().intent!!))
        assertEquals(MainActivity::class.java.name, manager.pinnedShortcuts.single().intent!!.component!!.className)
    }
    @Test fun `unsupported launcher cannot accept a pin and unrelated or malformed intents are not planner requests`() {
        shadowOf(manager).setIsRequestPinShortcutSupported(false)
        val platform = AndroidPlannerShortcut(app)
        assertFalse(platform.isSupported()); assertFalse(platform.request()); assertTrue(manager.pinnedShortcuts.isEmpty())
        val request = PlannerShortcutLinks.intent(app)
        assertTrue(PlannerShortcutLinks.matches(request))
        assertFalse(PlannerShortcutLinks.matches(Intent(request).setAction(Intent.ACTION_MAIN)))
        assertFalse(PlannerShortcutLinks.matches(Intent(request).setData(Uri.parse("notels://diary"))))
        assertFalse(PlannerShortcutLinks.matches(Intent(Intent.ACTION_VIEW)))
    }
}
