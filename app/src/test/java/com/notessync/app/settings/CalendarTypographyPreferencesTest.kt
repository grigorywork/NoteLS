package com.notessync.app.settings

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class CalendarTypographyPreferencesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private fun reload(): SettingsRepository {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible=true; set(null,null) }
        return SettingsRepository.get(app)
    }
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit(); reload() }
    @Test fun `defaults inherit calendar font and preserve widget XML typography`() {
        val state=reload().state.value
        assertNull(state.calendarFont); assertEquals(CalendarTextSize.NORMAL,state.calendarTextSize)
        assertEquals(CalendarWidgetTypography(),state.calendarWidgetTypography)
    }
    @Test fun `choices survive reload and resets preserve other scope color and completion`() {
        val repository=reload()
        repository.setAppFont(AppFont.INTER); repository.setWidgetAlpha(.6f)
        repository.setWidgetColoredTasks(true); repository.setWidgetCompletedStyle(WidgetCompletedStyle.GREEN)
        repository.setCalendarTypography(AppFont.PT_SANS,CalendarTextSize.LARGE)
        val widget=CalendarWidgetTypography(CalendarWidgetFont.MONO,CalendarTextSize.COMPACT)
        repository.setCalendarWidgetTypography(widget)
        val fresh=reload()
        assertEquals(AppFont.PT_SANS,fresh.state.value.calendarFont)
        assertEquals(CalendarTextSize.LARGE,fresh.state.value.calendarTextSize)
        assertEquals(widget,fresh.state.value.calendarWidgetTypography)
        fresh.setCalendarTypography(null,CalendarTextSize.NORMAL)
        assertEquals(widget,reload().state.value.calendarWidgetTypography)
        fresh.setCalendarWidgetTypography(CalendarWidgetTypography())
        val reset=reload().state.value
        assertNull(reset.calendarFont); assertEquals(AppFont.INTER,reset.appFont)
        assertEquals(.6f,reset.widgetAlpha); assertTrue(reset.widgetColoredTasks)
        assertEquals(WidgetCompletedStyle.GREEN,reset.widgetCompletedStyle)
    }
    @Test fun `unknown and wrong type values fail closed without touching unrelated values`() {
        prefs.edit().putInt("calendar_font",1).putString("calendar_text_size","NaN")
            .putInt("calendar_widget_font",3).putString("calendar_widget_text_size","100000")
            .putString("unrelated","keep").commit()
        val state=reload().state.value
        assertNull(state.calendarFont); assertEquals(CalendarTextSize.NORMAL,state.calendarTextSize)
        assertEquals(CalendarWidgetTypography(),state.calendarWidgetTypography)
        assertEquals("keep",prefs.getString("unrelated",null))
    }
    @Test fun `identical choices do not rewrite preferences or request widget rendering`() {
        val repository=reload(); var writes=0
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> writes++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        repository.setCalendarTypography(null,CalendarTextSize.NORMAL)
        repository.setCalendarWidgetTypography(CalendarWidgetTypography())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0,writes); assertFalse(prefs.contains("calendar_widget_font"))
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
