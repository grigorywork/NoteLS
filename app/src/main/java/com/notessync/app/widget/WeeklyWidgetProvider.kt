package com.notessync.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

class WeeklyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    override fun onEnabled(context: Context) { WeeklyWidgetUpdates.startIfPresent(context) }
    override fun onDisabled(context: Context) { WeeklyWidgetUpdates.stop() }
    override fun onDeleted(context: Context, ids: IntArray) {
        prefs(context).edit().apply { ids.forEach { remove("offset_$it"); remove("day_$it") } }.apply()
        WeeklyWidgetUpdates.startIfPresent(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PREVIOUS, NEXT, CURRENT -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (!installed(context, id)) return
                val current = offset(context, id)
                val next = when (intent.action) { PREVIOUS -> current - 1; NEXT -> current + 1; else -> 0 }
                prefs(context).edit().putInt("offset_$id", next.coerceIn(-MAX_WEEKS, MAX_WEEKS)).remove("day_$id").apply()
                refresh(context)
            }
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh(context)
        }
    }
    private fun refresh(context: Context) {
        val pending = goAsync()
        WeeklyWidgetUpdates.request(context) { pending?.finish() }
    }
    companion object {
        internal const val PREVIOUS = "com.notessync.app.widget.WEEK_PREVIOUS"
        internal const val NEXT = "com.notessync.app.widget.WEEK_NEXT"
        internal const val CURRENT = "com.notessync.app.widget.WEEK_CURRENT"
        const val EXTRA_WEEK_WIDGET_ID = "weekWidgetId"
        internal const val MAX_WEEKS = 520
        private fun prefs(context: Context) = context.getSharedPreferences("widget_week", Context.MODE_PRIVATE)
        private fun offset(context: Context, id: Int) = runCatching { prefs(context).getInt("offset_$id", 0) }.getOrDefault(0).coerceIn(-MAX_WEEKS, MAX_WEEKS)
        private fun installed(context: Context, id: Int) = id in AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, WeeklyWidgetProvider::class.java))
        internal fun monday(today: LocalDate, offset: Int = 0): LocalDate =
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.coerceIn(-MAX_WEEKS, MAX_WEEKS).toLong())
        internal fun state(context: Context, id: Int, today: LocalDate = LocalDate.now()): WeekSelection {
            val first = monday(today, offset(context, id))
            val fallback = if (today >= first && today <= first.plusDays(6)) today.toEpochDay() else first.toEpochDay()
            val saved = runCatching { prefs(context).getLong("day_$id", fallback) }.getOrDefault(fallback)
            return WeekSelection(first, saved.takeIf { it in first.toEpochDay()..first.plusDays(6).toEpochDay() } ?: fallback)
        }
        fun selectDay(context: Context, id: Int, day: Long) {
            if (!installed(context, id)) return
            val first = state(context, id).first.toEpochDay()
            if (day !in first..first + 6) return
            prefs(context).edit().putLong("day_$id", day).apply()
            WeeklyWidgetUpdates.request(context)
        }
    }
}
internal data class WeekSelection(val first: LocalDate, val selected: Long)
