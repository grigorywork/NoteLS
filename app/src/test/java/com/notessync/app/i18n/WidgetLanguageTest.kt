package com.notessync.app.i18n

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.TextView
import com.notessync.app.R
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.widget.CalendarWidgetProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru")
class WidgetLanguageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        // Robolectric replaces Application between methods; production has one per process.
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN)
    }
    @After fun after() {
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }
    @Test fun `language is persisted independently from device locale`() {
        val locale = Locale.getDefault(); val settings = SettingsRepository.get(app)
        settings.setUiLanguage(UiLanguage.ENGLISH)
        assertEquals("ENGLISH", app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("ui_language", null))
        assertEquals(UiLanguage.ENGLISH, settings.state.value.uiLanguage)
        assertEquals("en", UiStrings.locale.language); assertEquals(locale, Locale.getDefault())
    }
    @Test fun `missing and invalid preferences default to Russian`() {
        val settings = SettingsRepository.get(app)
        settings.setUiLanguage(UiLanguage.ENGLISH)
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("ui_language", "unknown-value").apply()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(UiLanguage.RUSSIAN, settings.state.value.uiLanguage)
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("ui_language").apply()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(UiLanguage.RUSSIAN, settings.state.value.uiLanguage)
    }
    @Test fun `widget month weekdays and accessible buttons follow app language while its selected day stays intact`() {
        val settings = SettingsRepository.get(app); settings.setUiLanguage(UiLanguage.ENGLISH)
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(CalendarWidgetProvider::class.java, R.layout.widget_calendar)
        val month = CalendarWidgetProvider.currentMonth(app, id)
        val day = month.atDay(1).toEpochDay(); CalendarWidgetProvider.selectDay(app, id, day)
        var view = manager.getViewFor(id)
        assertEquals("Mon", view.findViewById<TextView>(R.id.widget_weekday_mon).text.toString())
        assertTrue(view.findViewById<TextView>(R.id.widget_month_title).text.toString().startsWith(month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.US)))
        assertEquals("Quick task", view.findViewById<View>(R.id.widget_add).contentDescription.toString())
        settings.setUiLanguage(UiLanguage.RUSSIAN)
        view = manager.getViewFor(id)
        assertEquals("Пн", view.findViewById<TextView>(R.id.widget_weekday_mon).text.toString())
        assertEquals("Быстрая задача", view.findViewById<View>(R.id.widget_add).contentDescription.toString())
        assertEquals(month, CalendarWidgetProvider.currentMonth(app, id))
        assertTrue(view.findViewById<View>(R.id.widget_add).performClick())
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(day, intent.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1L))
    }
}
