package com.notessync.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.notessync.app.R
import com.notessync.app.settings.SettingsRepository
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Полноэкранный виджет-календарь (Этап 8).
 *  Тап по любой дате открывает быструю заметку этого дня.
 *  Кнопка «⋮» в правом верхнем углу открывает настройки виджета (Этап 28). */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_MONTH_PREV, ACTION_MONTH_NEXT -> {
                val delta = if (intent.action == ACTION_MONTH_PREV) -1 else 1
                monthPrefs(context).edit().putInt(
                    keyMonth(id),
                    monthPrefs(context).getInt(keyMonth(id), 0) + delta
                ).apply()
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) updateWidget(context, manager, id)
            }
            ACTION_REFRESH -> refreshAll(context)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { monthPrefs(context).edit().remove(keyMonth(it)).apply() }
    }

    companion object {
        const val ACTION_MONTH_PREV = "com.notessync.app.widget.MONTH_PREV"
        const val ACTION_MONTH_NEXT = "com.notessync.app.widget.MONTH_NEXT"
        const val ACTION_REFRESH = "com.notessync.app.widget.REFRESH"
        const val EXTRA_DAY_KEY = "dayKey"

        private const val PREFS_MONTH = "widget_calendar"
        private fun monthPrefs(context: Context) =
            context.getSharedPreferences(PREFS_MONTH, Context.MODE_PRIVATE)
        private fun keyMonth(id: Int) = "month_offset_$id"

        fun currentMonth(context: Context, id: Int): YearMonth =
            YearMonth.now().plusMonths(monthPrefs(context).getInt(keyMonth(id), 0).toLong())

        /** Полная перерисовка виджета: шапка, сетка, прозрачность. */
        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val month = currentMonth(context, id)
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)

            val monthName = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))
                .replaceFirstChar { it.uppercase(Locale("ru")) }
            views.setTextViewText(R.id.widget_month_title, "$monthName, ${month.year}")

            // Прозрачность фона — из настроек (выбирается в меню «⋮», этап 28).
            val alpha = SettingsRepository.get(context).state.value.widgetAlpha
            views.setInt(R.id.widget_bg, "setImageAlpha", (alpha * 255).roundToInt())

            views.setOnClickPendingIntent(
                R.id.widget_prev, selfIntent(context, id, ACTION_MONTH_PREV)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next, selfIntent(context, id, ACTION_MONTH_NEXT)
            )
            // Меню «⋮»: настройки виджета (прозрачность) в отдельной задаче,
            // как и быстрая заметка — приложение не всплывает при закрытии.
            views.setOnClickPendingIntent(
                R.id.widget_menu_button,
                PendingIntent.getActivity(
                    context, id,
                    Intent(context, WidgetSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val adapterIntent = Intent(context, CalendarWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = android.net.Uri.parse("notessync://widget/$id/${System.currentTimeMillis()}")
            }
            views.setRemoteAdapter(R.id.calendar_grid, adapterIntent)

            // Шаблон интента: конкретная дата подставляется через fillInIntent ячейки.
            // NEW_TASK + пустой taskAffinity в манифесте: диалог живёт в своей задаче,
            // при закрытии под ним не всплывает MainActivity (этап 26).
            val template = PendingIntent.getActivity(
                context, id,
                Intent(context, QuickNoteActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.calendar_grid, template)

            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid)
        }

        private val lastRefreshAt = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * Полная перерисовка всех виджетов. Аудит 2026-11-11: автосохранение
         * заметки дня вызывает refreshAll каждые 500 мс при наборе текста —
         * пересборка RemoteViews и запрос БД столько раз в секунду. Поэтому
         * неявные обновления (из автосохранения) идут не чаще REFRESH_THROTTLE_MS,
         * а явные действия (удаление, галочка задачи) — сразу, force = true.
         */
        fun refreshAll(context: Context, force: Boolean = true) {
            if (!force) {
                val now = System.currentTimeMillis()
                val previous = lastRefreshAt.get()
                if (now - previous < REFRESH_THROTTLE_MS) return
                if (!lastRefreshAt.compareAndSet(previous, now)) return
            }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CalendarWidgetProvider::class.java)
            )
            ids.forEach { updateWidget(context, manager, it) }
        }

        private const val REFRESH_THROTTLE_MS = 2_000L

        private fun selfIntent(context: Context, id: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, id * 31 + action.hashCode(),
                Intent(context, CalendarWidgetProvider::class.java)
                    .setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun dayKeyToday(): Long = LocalDate.now().toEpochDay()
    }
}
