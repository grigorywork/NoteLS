package com.notessync.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Parcel
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.style.*
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.notessync.app.R
import com.notessync.app.settings.*
import com.notessync.app.data.local.model.WidgetDaySnippet
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="ru-w320dp-h640dp")
class CalendarTypographyWidgetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val typography=CalendarWidgetTypography(CalendarWidgetFont.MONO,CalendarTextSize.LARGE)
    @Before fun before() {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible=true; set(null,null) }
    }
    @After fun after() { WeeklyWidgetUpdates.stop() }
    private fun parcel(views: RemoteViews): RemoteViews {
        val parcel=Parcel.obtain()
        return try { views.writeToParcel(parcel,0); parcel.setDataPosition(0); RemoteViews.CREATOR.createFromParcel(parcel) }
        finally { parcel.recycle() }
    }
    private fun assertFont(text: CharSequence, family: String) {
        val spans=(text as Spanned).getSpans(0,text.length,TypefaceSpan::class.java)
        assertEquals(listOf(family),spans.map { it.family })
    }
    @Test fun `all system families survive RemoteViews IPC preserving color strike and literal user text`() {
        val title="☑ Кириллица %1"+'$'+"s 😀"
        CalendarWidgetFont.entries.forEach { font ->
            val text=SpannableStringBuilder(title).apply {
                setSpan(ForegroundColorSpan(0xff81c784.toInt()),0,length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StrikethroughSpan(),0,length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val views=RemoteViews(app.packageName,R.layout.widget_day_cell)
            CalendarWidgetText.set(views,R.id.day_entries,text,11f,typography.copy(font=font))
            val row=parcel(views).apply(app,FrameLayout(app)).findViewById<TextView>(R.id.day_entries)
            assertEquals(title,row.text.toString()); assertEquals(11f*1.15f*app.resources.displayMetrics.scaledDensity,row.textSize,.01f)
            val styled=row.text as Spanned
            assertEquals(1,styled.getSpans(0,styled.length,StrikethroughSpan::class.java).size)
            assertEquals(0xff81c784.toInt(),styled.getSpans(0,styled.length,ForegroundColorSpan::class.java).single().foregroundColor)
            if (font.family != null) assertFont(row.text,font.family!!)
            else assertTrue(styled.getSpans(0,styled.length,TypefaceSpan::class.java).isEmpty())
        }
    }
    @Test fun `week rows and header use selected typography and reapply reset removes old spans`() {
        val first=LocalDate.of(2026,11,23); val selection=WeekSelection(first,first.toEpochDay())
        val days=mapOf(first.toEpochDay() to WidgetDaySnapshot(listOf(WidgetDaySnippet(1,first.toEpochDay(),"DONE",true,true,"mint")),1))
        val common=CompactWidgetPreferences.from(SettingsState(widgetColoredTasks=true))
        val root=parcel(WeeklyWidgetViews.build(app,101,selection,days,common,typography=typography)).apply(app,FrameLayout(app))
        val row=root.findViewById<TextView>(R.id.week_day_0)
        assertFont(row.text,"monospace"); assertFont(root.findViewById<TextView>(R.id.week_title).text,"monospace")
        assertTrue(row.text.toString().contains("DONE")); assertEquals(14*1.15f*app.resources.displayMetrics.scaledDensity,row.textSize,.01f)
        val styled=row.text as Spanned
        assertTrue(styled.getSpans(0,styled.length,StyleSpan::class.java).isNotEmpty())
        assertEquals(1,styled.getSpans(0,styled.length,StrikethroughSpan::class.java).size)
        parcel(WeeklyWidgetViews.build(app,101,selection,days,common)).reapply(app,root)
        assertTrue((row.text as Spanned).getSpans(0,row.text.length,TypefaceSpan::class.java).isEmpty())
        assertEquals(14f*app.resources.displayMetrics.scaledDensity,row.textSize,.01f)
    }
    @Test fun `month header typography change bypasses render signature cache and reset restores default`() {
        val settings=SettingsRepository.get(app)
        val manager=shadowOf(AppWidgetManager.getInstance(app))
        val id=manager.createWidget(CalendarWidgetProvider::class.java,R.layout.widget_calendar)
        settings.setCalendarWidgetTypography(typography)
        var title=manager.getViewFor(id).findViewById<TextView>(R.id.widget_month_title)
        assertFont(title.text,"monospace"); assertEquals(16*1.15f*app.resources.displayMetrics.scaledDensity,title.textSize,.01f)
        settings.setCalendarWidgetTypography(CalendarWidgetTypography())
        CalendarWidgetProvider.updateWidget(app,AppWidgetManager.getInstance(app),id,force=false)
        title=manager.getViewFor(id).findViewById(R.id.widget_month_title)
        assertEquals(16f*app.resources.displayMetrics.scaledDensity,title.textSize,.01f)
        assertFalse(title.text is Spanned && (title.text as Spanned).getSpans(0,title.text.length,TypefaceSpan::class.java).isNotEmpty())
    }
    @Test fun `weekly cache changes for typography while compact note preferences remain equal`() {
        val original=SettingsState(); val changed=original.copy(calendarWidgetTypography=typography)
        assertNotEquals(WeeklyWidgetPreferences.from(original),WeeklyWidgetPreferences.from(changed))
        assertEquals(CompactWidgetPreferences.from(original),CompactWidgetPreferences.from(changed))
        assertEquals(WeeklyWidgetPreferences.from(changed),WeeklyWidgetPreferences.from(changed.copy(calendarFont=AppFont.INTER)))
    }
}
