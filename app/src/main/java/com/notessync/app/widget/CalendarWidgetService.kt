package com.notessync.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.notessync.app.R
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.WidgetCompletedStyle
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.YearMonth

/** Источник ячеек сетки календаря: 42 ячейки (6 недель), понедельник первый. */
class CalendarWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        CalendarFactory(applicationContext, intent)
}

class CalendarFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(
        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
        android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private data class Cell(val date: LocalDate, val inMonth: Boolean)

    private var cells: List<Cell> = emptyList()
    private var dayEntries: Map<Long, List<WidgetDayEntry>> = emptyMap()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val month = CalendarWidgetProvider.currentMonth(context, widgetId)
        val firstOfMonth = month.atDay(1)
        // Сдвиг до понедельника: dayOfWeek.value 1..7 (пн..вс).
        val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
        cells = (0 until 42).map { offset ->
            val date = gridStart.plusDays(offset.toLong())
            Cell(date, YearMonth.from(date) == month)
        }
        dayEntries = try {
            runBlocking {
                AppDatabase.get(context).noteDao()
                    .getForDayRange(gridStart.toEpochDay(), gridStart.plusDays(41).toEpochDay())
                    .groupBy { it.dayKey!! }
                    .mapValues { (_, notes) ->
                        notes.map { WidgetDayEntry(it.title, it.isTask, it.isCompleted, it.taskColor) }
                    }
            }
        } catch (e: Exception) {
            emptyMap()
        }
        // Настройки читаем при каждом обновлении данных виджета.
        val settings = SettingsRepository.get(context).state.value
        coloredTasks = settings.widgetColoredTasks
        completedStyle = settings.widgetCompletedStyle
    }

    private var coloredTasks: Boolean = false
    private var completedStyle: WidgetCompletedStyle = WidgetCompletedStyle.BOTH

    override fun getCount(): Int = cells.size

    override fun getViewAt(position: Int): RemoteViews {
        val cell = cells[position]
        val today = LocalDate.now()
        val views = RemoteViews(context.packageName, R.layout.widget_day_cell)
        views.setTextViewText(R.id.day_number, cell.date.dayOfMonth.toString())

        val color = when {
            cell.date == today -> Color.parseColor("#7C4DFF") // сегодня — фиолетовый акцент
            !cell.inMonth -> Color.parseColor("#66FFFFFF")    // соседний месяц — приглушённый
            else -> Color.WHITE
        }
        views.setTextColor(R.id.day_number, color)
        views.setTextColor(R.id.day_entries, color)
        val entries = dayEntries[cell.date.toEpochDay()].orEmpty()
        views.setTextViewText(R.id.day_entries, styledPreview(entries))

        // Тап по любой дате (даже соседнего месяца) — быстрая заметка этого дня.
        val fillIn = Intent().putExtra(
            CalendarWidgetProvider.EXTRA_DAY_KEY, cell.date.toEpochDay()
        )
        views.setOnClickFillInIntent(R.id.day_cell_root, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun onDestroy() {}

    /** Цвет задач и выбранный стиль выполнения применяются независимо друг от друга. */
    private fun styledPreview(entries: List<WidgetDayEntry>): CharSequence {
        val builder = SpannableStringBuilder()
        WidgetDayPreview.lines(entries).forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val start = builder.length
            builder.append(line.text)
            val end = builder.length
            val foreground = when {
                line.isCompleted && completedStyle.usesGreen -> COMPLETED_GREEN
                coloredTasks -> line.colorKey?.let(::widgetTaskColor)
                else -> null
            }
            foreground?.let { color ->
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (line.isCompleted && completedStyle.usesStrike) {
                builder.setSpan(
                    StrikethroughSpan(),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return builder
    }

    companion object {
        private const val COMPLETED_GREEN = 0xFF81C784.toInt()

        private val TASK_COLORS = mapOf(
            "violet" to "#DCC2FF",
            "blue" to "#B9D9FF",
            "mint" to "#A9E6D2",
            "yellow" to "#FFE082",
            "orange" to "#FFC29E",
            "pink" to "#FFC1D9"
        )

        fun widgetTaskColor(key: String): Int =
            Color.parseColor(TASK_COLORS[key] ?: TASK_COLORS.getValue("violet"))
    }
}
