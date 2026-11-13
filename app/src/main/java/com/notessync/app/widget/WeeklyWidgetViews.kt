package com.notessync.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.RemoteViews
import com.notessync.app.R
import com.notessync.app.i18n.UiCatalog
import com.notessync.app.notes.text.notePreviewText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal object WeeklyWidgetViews {
    val rows = intArrayOf(R.id.week_day_0, R.id.week_day_1, R.id.week_day_2, R.id.week_day_3,
        R.id.week_day_4, R.id.week_day_5, R.id.week_day_6)
    fun build(context: Context, id: Int, selection: WeekSelection, days: Map<Long, WidgetDaySnapshot>,
        preferences: CompactWidgetPreferences, today: LocalDate = LocalDate.now(), failed: Boolean = false): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_week)
        fun text(key: String) = UiCatalog.translate(preferences.language, key)
        views.setInt(R.id.week_bg, "setImageAlpha", (preferences.alpha * 255).roundToInt())
        val first = selection.first; val last = first.plusDays(6)
        val full = DateTimeFormatter.ofPattern("dd.MM.yyyy", preferences.language.locale)
        val range = "${first.format(full)} – ${last.format(full)}"
        val caption = if (failed) text("Не удалось обновить записи. Откройте NoteLS.") else
            if (first.month == last.month && first.year == last.year) "${first.dayOfMonth}–${last.dayOfMonth}\n${first.format(DateTimeFormatter.ofPattern("MMM yyyy", preferences.language.locale))}"
            else "${first.format(DateTimeFormatter.ofPattern("dd.MM.yy", preferences.language.locale))}\n–${last.format(DateTimeFormatter.ofPattern("dd.MM.yy", preferences.language.locale))}"
        views.setTextViewText(R.id.week_title, caption)
        views.setContentDescription(R.id.week_title, text("Текущая неделя") + ". " + range + if (failed) ". $caption" else "")
        listOf(R.id.week_prev to WeeklyWidgetProvider.PREVIOUS, R.id.week_next to WeeklyWidgetProvider.NEXT,
            R.id.week_title to WeeklyWidgetProvider.CURRENT).forEach { (viewId, action) ->
            val intent = Intent(context, WeeklyWidgetProvider::class.java).setAction(action)
                .setData(Uri.parse("notels://week/$id/$action")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            views.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (failed) {
            val home = Intent(context, com.notessync.app.MainActivity::class.java)
                .setData(Uri.parse("notels://week/$id/home"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(R.id.week_title, activity(context, id, home))
        }
        views.setContentDescription(R.id.week_prev, text("Предыдущая неделя"))
        views.setContentDescription(R.id.week_next, text("Следующая неделя"))
        views.setContentDescription(R.id.week_add, text("Быстрая задача"))
        views.setOnClickPendingIntent(R.id.week_add, dayIntent(context, id, "add", selection.selected, quick = true))
        views.setContentDescription(R.id.week_menu, text("Настройки виджета"))
        val menu = Intent(context, WidgetSettingsActivity::class.java).setData(Uri.parse("notels://week/$id/settings"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(R.id.week_menu, activity(context, id, menu))
        rows.forEachIndexed { index, viewId ->
            if (failed) {
                views.setTextViewText(viewId, ""); views.setContentDescription(viewId, "")
                views.setOnClickPendingIntent(viewId, null)
            } else {
                val date = first.plusDays(index.toLong())
                val dateText = (if (date.toEpochDay() == selection.selected) "› " else "") +
                    date.format(DateTimeFormatter.ofPattern("EEE, dd.MM", preferences.language.locale))
                val builder = SpannableStringBuilder(dateText)
                builder.setSpan(StyleSpan(Typeface.BOLD), 0, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(if (date == today) 0xFFDCC2FF.toInt() else Color.WHITE), 0, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append("\n")
                val snapshot = days[date.toEpochDay()] ?: WidgetDaySnapshot(emptyList(), 0)
                val lines = WidgetDayPreview.lines(snapshot.previews(), snapshot.total, preferences.language)
                if (lines.isEmpty()) builder.append(text("Пока нет записей"))
                lines.forEachIndexed { lineIndex, line ->
                    if (lineIndex > 0) builder.append(" · ")
                    val start = builder.length
                    builder.append(notePreviewText(line.text, 80))
                    val end = builder.length
                    val color = when {
                        line.isCompleted && preferences.completed.usesGreen -> 0xFF81C784.toInt()
                        preferences.coloredTasks -> line.colorKey?.let(CalendarFactory::widgetTaskColor)
                        else -> null
                    }
                    color?.let { builder.setSpan(ForegroundColorSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    if (line.isCompleted && preferences.completed.usesStrike) builder.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                views.setTextViewText(viewId, builder)
                views.setContentDescription(viewId, date.format(full) + ". " + builder)
                views.setOnClickPendingIntent(viewId, dayIntent(context, id, "row/$index", date.toEpochDay(), quick = false))
            }
        }
        return views
    }
    private fun dayIntent(context: Context, id: Int, slot: String, day: Long, quick: Boolean): PendingIntent {
        val intent = QuickNoteActivity.intent(context, day, startWithTime = quick)
            .putExtra(WeeklyWidgetProvider.EXTRA_WEEK_WIDGET_ID, id).setData(Uri.parse("notels://week/$id/$slot"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return activity(context, id, intent)
    }
    private fun activity(context: Context, id: Int, intent: Intent) = PendingIntent.getActivity(context, id, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
