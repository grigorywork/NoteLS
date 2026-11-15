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
@Config(sdk = [28], application = Application::class)
class GlassPreferencesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    @Before fun before() { settings.setGlassAppearance(GlassAppearance()) }
    @After fun after() { settings.setGlassAppearance(GlassAppearance()); settings.setInterfaceStyle(InterfaceStyle.CLASSIC) }
    @Test fun `whole appearance survives style toggles without changing unrelated preferences`() {
        val old = settings.state.value
        val custom = GlassAppearance(0.9f, 0.7f, GlassTint.TEAL, GlassBackdrop.BANDS)
        settings.setGlassAppearance(custom)
        settings.setInterfaceStyle(InterfaceStyle.CLASSIC); settings.setInterfaceStyle(InterfaceStyle.GLASS)
        assertEquals(custom, settings.state.value.glassAppearance)
        assertEquals(old.copy(glassAppearance = custom, interfaceStyle = InterfaceStyle.GLASS), settings.state.value)
    }
    @Test fun `corrupt individual preferences do not discard other valid fields`() {
        prefs.edit().putString("glass_transparency", "wrong type").putFloat("glass_frost", 0.8f)
            .putString("glass_tint", "OLD_UNKNOWN").putString("glass_backdrop", "BANDS").commit()
        assertEquals(GlassAppearance(0.5f, 0.8f, GlassTint.THEME, GlassBackdrop.BANDS), settings.state.value.glassAppearance)
        prefs.edit().putFloat("glass_transparency", Float.NaN).putFloat("glass_frost", Float.POSITIVE_INFINITY).commit()
        assertEquals(GlassAppearance(backdrop = GlassBackdrop.BANDS), settings.state.value.glassAppearance)
    }
    @Test fun `sanitized save persists all four fields together`() {
        settings.setGlassAppearance(GlassAppearance(-2f, 7f, GlassTint.ROSE, GlassBackdrop.SOLID))
        assertEquals(GlassAppearance(0f, 1f, GlassTint.ROSE, GlassBackdrop.SOLID), settings.state.value.glassAppearance)
        assertEquals(0f, prefs.getFloat("glass_transparency", -1f), 0f)
        assertEquals("ROSE", prefs.getString("glass_tint", null))
    }
}
