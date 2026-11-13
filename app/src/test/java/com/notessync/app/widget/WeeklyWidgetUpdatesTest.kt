package com.notessync.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.notessync.app.R
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WeeklyWidgetUpdatesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() = runBlocking {
        WeeklyWidgetUpdates.stop()?.join(); CompactWidgetUpdates.stop()?.join()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN); ProtectionSession.lockAll()
    }
    @After fun after() = runBlocking {
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN); shadowOf(Looper.getMainLooper()).idle()
        WeeklyWidgetUpdates.stop()?.join(); CompactWidgetUpdates.stop()?.join()
        AppDatabase.closeInstance(); ProtectionSession.lockAll()
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        Unit
    }
    private suspend fun refresh() {
        val done = CompletableDeferred<Unit>(); WeeklyWidgetUpdates.request(app) { done.complete(Unit) }
        withTimeout(10_000) { done.await() }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }
    @Test fun `Room events preserve the final title and remove private parent content while installed observers stop and restart`() = runBlocking {
        val db = AppDatabase.get(app); val first = WeeklyWidgetProvider.monday(LocalDate.now())
        val book = NotebookEntity(name = "Public").let { it.copy(id = db.notebookDao().upsert(it)) }
        val note = NoteEntity(title = "Initial week title", dayKey = first.toEpochDay(), notebookId = book.id).let { it.copy(id = db.noteDao().upsert(it)) }
        val manager = shadowOf(AppWidgetManager.getInstance(app)); val id = manager.createWidget(WeeklyWidgetProvider::class.java, R.layout.widget_week)
        refresh()
        fun row() = manager.getViewFor(id).findViewById<TextView>(R.id.week_day_0).text.toString()
        assertTrue(row().contains(note.title))
        db.noteDao().update(note.copy(title = "First edit")); db.noteDao().update(note.copy(title = "Final edit"))
        await { row().contains("Final edit") }; delay(350); assertTrue(row().contains("Final edit"))
        SettingsRepository.get(app).setUiLanguage(UiLanguage.ENGLISH)
        await { manager.getViewFor(id).findViewById<View>(R.id.week_next).contentDescription == "Next week" }
        ProtectionSession.unlockNotebook(book.id); db.notebookDao().update(book.copy(isProtected = true))
        await { row().contains("No entries yet") }; assertFalse(row().contains("Final edit"))
        WeeklyWidgetUpdates.stop()?.join(); assertNull(WeeklyWidgetUpdates.stop())
        db.notebookDao().update(book); db.noteDao().update(note.copy(title = "After restart")); delay(350)
        assertTrue(row().contains("No entries yet")); refresh(); assertTrue(row().contains("After restart"))
    }
    @Test fun `widget week navigation and selected add day stay independent and unknown widget IDs are refused`() = runBlocking {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val one = manager.createWidget(WeeklyWidgetProvider::class.java, R.layout.widget_week)
        val two = manager.createWidget(WeeklyWidgetProvider::class.java, R.layout.widget_week)
        val first = WeeklyWidgetProvider.state(app, one).first
        val day = first.plusDays(6).toEpochDay(); WeeklyWidgetProvider.selectDay(app, one, day); refresh()
        assertTrue(manager.getViewFor(one).findViewById<View>(R.id.week_add).performClick())
        assertEquals(day, shadowOf(app).nextStartedActivity.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
        val provider = WeeklyWidgetProvider()
        provider.onReceive(app, Intent(WeeklyWidgetProvider.NEXT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, one)); refresh()
        assertEquals(first.plusWeeks(1), WeeklyWidgetProvider.state(app, one).first)
        assertEquals(first, WeeklyWidgetProvider.state(app, two).first)
        assertTrue(manager.getViewFor(two).findViewById<View>(R.id.week_add).performClick())
        assertEquals(WeeklyWidgetProvider.state(app, two).selected, shadowOf(app).nextStartedActivity.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
        assertEquals(first.plusWeeks(1).toEpochDay(), WeeklyWidgetProvider.state(app, one).selected)
        assertTrue(manager.getViewFor(one).findViewById<View>(R.id.week_day_0).performClick())
        assertEquals(first.plusWeeks(1).toEpochDay(), shadowOf(app).nextStartedActivity.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
        provider.onReceive(app, Intent(WeeklyWidgetProvider.CURRENT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, one)); refresh()
        assertEquals(first, WeeklyWidgetProvider.state(app, one).first)
        provider.onReceive(app, Intent(WeeklyWidgetProvider.NEXT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 999_999))
        assertFalse(app.getSharedPreferences("widget_week", 0).contains("offset_999999"))
        app.getSharedPreferences("widget_week", 0).edit().putInt("offset_$one", Int.MAX_VALUE).putLong("day_$one", Long.MIN_VALUE).apply()
        assertEquals(first.plusWeeks(520), WeeklyWidgetProvider.state(app, one).first)
        assertEquals(first.plusWeeks(520).toEpochDay(), WeeklyWidgetProvider.state(app, one).selected)
        provider.onDeleted(app, intArrayOf(one)); assertFalse(app.getSharedPreferences("widget_week", 0).contains("offset_$one"))
    }
    @Test fun `clock notifications refresh relative week selections and no periodic update is configured`() = runBlocking {
        val manager = shadowOf(AppWidgetManager.getInstance(app)); val id = manager.createWidget(WeeklyWidgetProvider::class.java, R.layout.widget_week)
        refresh()
        app.getSharedPreferences("widget_week", 0).edit().putInt("offset_$id", 2).apply()
        WeeklyWidgetProvider().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        val expected = WeeklyWidgetProvider.monday(LocalDate.now(), 2)
        await { manager.getViewFor(id).findViewById<TextView>(R.id.week_day_0).text.toString().contains(expected.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"))) }
        assertTrue(manager.getViewFor(id).findViewById<View>(R.id.week_day_0).performClick())
        assertEquals(WeeklyWidgetProvider.monday(LocalDate.now(), 2).toEpochDay(), shadowOf(app).nextStartedActivity.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
        app.resources.getXml(R.xml.widget_week_info).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
            assertEquals("0", parser.getAttributeValue("http://schemas.android.com/apk/res/android", "updatePeriodMillis"))
        }
    }
}
