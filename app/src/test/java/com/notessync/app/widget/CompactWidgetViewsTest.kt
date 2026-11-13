package com.notessync.app.widget

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.text.Spanned
import android.text.style.StrikethroughSpan
import com.notessync.app.R
import com.notessync.app.data.local.model.CompactWidgetEntry
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w180dp-h208dp")
class CompactWidgetViewsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = CompactWidgetPreferences.from(SettingsState(uiLanguage = UiLanguage.ENGLISH, widgetColoredTasks = true))
    private fun entry(id: Long = 7, title: String = "Удалить %1" + '$' + "s 😀") =
        CompactWidgetEntry(id, "sync-$id", title, "", true, true, true, "mint", "")
    @Test fun `real RemoteViews keep user text and styles and open the exact row plus current-day quick task`() {
        val row = entry()
        val root = CompactWidgetViews.build(app, 40, listOf(row), prefs).apply(app, FrameLayout(app))
        val text = root.findViewById<TextView>(R.id.compact_row_0)
        assertTrue(text.text.toString().contains(row.title)); assertTrue((text.text as Spanned).getSpans(0, text.text.length, StrikethroughSpan::class.java).isNotEmpty())
        assertTrue(text.contentDescription.toString().startsWith("Open “"))
        assertTrue(text.performClick()); val intent = shadowOf(app).nextStartedActivity
        assertEquals(row.id, WidgetNoteLinks.read(intent, 1)!!.noteId); assertEquals(row.syncId, WidgetNoteLinks.read(intent, 2)!!.syncId)
        assertFalse(intent.extras!!.containsKey("title"))
        assertTrue(root.findViewById<View>(R.id.compact_add).performClick()); val add = shadowOf(app).nextStartedActivity
        assertEquals(QuickNoteActivity::class.java.name, add.component!!.className)
        assertTrue(add.getBooleanExtra(QuickNoteActivity.EXTRA_QUICK_ADD, false)); assertFalse(add.hasExtra(CalendarWidgetProvider.EXTRA_DAY_KEY))
        assertEquals("Quick task", root.findViewById<View>(R.id.compact_add).contentDescription.toString())
        assertTrue(root.findViewById<View>(R.id.compact_menu).performClick()); assertEquals(WidgetSettingsActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
    }
    @Test fun `reapplication clears all stale row text and click actions and handles a corrupt alpha`() {
        val first = CompactWidgetViews.build(app, 42, listOf(entry()), prefs)
        val root = first.apply(app, FrameLayout(app))
        val next = CompactWidgetViews.build(app, 42, emptyList(), CompactWidgetPreferences.from(SettingsState(uiLanguage = UiLanguage.ENGLISH, widgetAlpha = Float.NaN)))
        next.reapply(app, root)
        CompactWidgetViews.rows.forEach { id ->
            assertEquals("", root.findViewById<TextView>(id).text.toString()); assertEquals(View.GONE, root.findViewById<View>(id).visibility)
            assertFalse(root.findViewById<View>(id).performClick())
        }
        assertEquals("No entries yet", root.findViewById<TextView>(R.id.compact_empty).text.toString())
        assertTrue(root.findViewById<ImageView>(R.id.compact_bg).imageAlpha in 1..255)
    }
    @Test fun `minimum layout keeps all three rows and touch targets without a collection service`() {
        val root = CompactWidgetViews.build(app, 43, listOf(entry(1), entry(2), entry(3)), prefs).apply(app, FrameLayout(app))
        val density = app.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((180 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((208 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        CompactWidgetViews.rows.forEach { id -> assertTrue(root.findViewById<View>(id).height >= (48 * density).toInt()) }
        assertTrue(root.findViewById<View>(R.id.compact_add).width >= (48 * density).toInt())
        assertEquals(View.GONE, root.findViewById<View>(R.id.compact_empty).visibility)
    }
}
