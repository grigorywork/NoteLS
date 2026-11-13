package com.notessync.app.widget

import android.app.Application
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.notessync.app.R
import com.notessync.app.data.local.model.WidgetDaySnippet
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w272dp-h400dp")
class WeeklyWidgetViewsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val first = LocalDate.of(2020, 12, 28)
    private val selection get() = WeekSelection(first, first.plusDays(3).toEpochDay())
    private val prefs get() = CompactWidgetPreferences.from(SettingsState(uiLanguage = UiLanguage.ENGLISH, widgetColoredTasks = true))
    private val title = "Удалить %1" + '$' + "s 😀"
    private fun entries() = mapOf(first.toEpochDay() to WidgetDaySnapshot(listOf(WidgetDaySnippet(1, first.toEpochDay(), title, true, true, "mint")), 8))
    @Test fun `week views preserve user text and completed style and every date opens the shared day without forcing time selection`() {
        val root = WeeklyWidgetViews.build(app, 20, selection, entries(), prefs, today = first).apply(app, FrameLayout(app))
        val row = root.findViewById<TextView>(R.id.week_day_0)
        assertTrue(row.text.toString().contains(title)); assertTrue(row.text.toString().contains("+7"))
        assertTrue((row.text as Spanned).getSpans(0, row.text.length, StrikethroughSpan::class.java).isNotEmpty())
        WeeklyWidgetViews.rows.forEachIndexed { index, view ->
            assertTrue(root.findViewById<View>(view).performClick()); val intent = shadowOf(app).nextStartedActivity
            assertEquals(QuickNoteActivity::class.java.name, intent.component!!.className)
            assertEquals(first.plusDays(index.toLong()).toEpochDay(), intent.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
            assertFalse(intent.getBooleanExtra(QuickNoteActivity.EXTRA_QUICK_ADD, true))
            assertEquals(20, intent.getIntExtra(WeeklyWidgetProvider.EXTRA_WEEK_WIDGET_ID, -1))
        }
        assertTrue(root.findViewById<View>(R.id.week_add).performClick()); val add = shadowOf(app).nextStartedActivity
        assertEquals(selection.selected, add.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1)); assertTrue(add.getBooleanExtra(QuickNoteActivity.EXTRA_QUICK_ADD, false))
        assertEquals("Previous week", root.findViewById<View>(R.id.week_prev).contentDescription)
        assertTrue(root.findViewById<View>(R.id.week_title).contentDescription.toString().contains("03.01.2021"))
    }
    @Test fun `same row slots replace old day extras and an error removes all old text and click actions`() {
        val root = WeeklyWidgetViews.build(app, 21, selection, entries(), prefs).apply(app, FrameLayout(app))
        val next = WeekSelection(first.plusWeeks(1), first.plusWeeks(1).toEpochDay())
        WeeklyWidgetViews.build(app, 21, next, emptyMap(), prefs).reapply(app, root)
        assertTrue(root.findViewById<View>(R.id.week_day_0).performClick())
        assertEquals(next.first.toEpochDay(), shadowOf(app).nextStartedActivity.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
        assertFalse(root.findViewById<TextView>(R.id.week_day_0).text.toString().contains(title))
        WeeklyWidgetViews.build(app, 21, next, emptyMap(), prefs, failed = true).reapply(app, root)
        WeeklyWidgetViews.rows.forEach { id -> assertEquals("", root.findViewById<TextView>(id).text.toString()); assertFalse(root.findViewById<View>(id).performClick()) }
        assertTrue(root.findViewById<TextView>(R.id.week_title).text.toString().contains("Could not update"))
        assertTrue(root.findViewById<View>(R.id.week_title).performClick())
        assertEquals(com.notessync.app.MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
    }
    @Test fun `minimum widget shows all seven dates with accessible row and header targets`() {
        val root = WeeklyWidgetViews.build(app, 22, selection, emptyMap(), prefs).apply(app, FrameLayout(app))
        val density = app.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((272 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        (WeeklyWidgetViews.rows.toList() + listOf(R.id.week_title, R.id.week_add, R.id.week_menu, R.id.week_prev, R.id.week_next)).forEach { id ->
            val view = root.findViewById<View>(id); assertTrue("Height of $id", view.height >= (48 * density).toInt()); assertTrue(view.width >= (48 * density).toInt())
        }
    }
}
