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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.notessync.app.i18n.UiCatalog
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
                ).remove(keyDay(id)).apply()
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) updateWidget(context, manager, id)
            }
            ACTION_REFRESH -> refreshAll(context)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            monthPrefs(context).edit().remove(keyMonth(it)).remove(keyDay(it)).apply()
            rendered.remove(it)
            lastNotifiedAt.remove(it)
        }
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
        private fun keyDay(id: Int) = "selected_day_$id"
        fun selectDay(context: Context, id: Int, day: Long) {
            val manager = AppWidgetManager.getInstance(context)
            if (id !in manager.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))) return
            monthPrefs(context).edit().putLong(keyDay(id), day).apply()
            updateWidget(context, AppWidgetManager.getInstance(context), id)
        }
        private fun selectedDay(context: Context, id: Int): Long {
            val month = currentMonth(context, id)
            val fallback = if (month == YearMonth.now()) LocalDate.now().toEpochDay() else month.atDay(1).toEpochDay()
            return monthPrefs(context).getLong(keyDay(id), fallback)
        }

        fun currentMonth(context: Context, id: Int): YearMonth =
            YearMonth.now().plusMonths(monthPrefs(context).getInt(keyMonth(id), 0).toLong())

        /** Полная перерисовка виджета: шапка, сетка, прозрачность.
         *  Повторная перерисовка с тем же снимком (автосохранение текста) только
         *  перезапрашивает данные сетки: частая привязка RemoteViews через Binder
         *  раньше копилась в лаунчере и виджет со временем переставал отвечать. */
        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int, force: Boolean = true) {
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val settings = SettingsRepository.get(context).state.value
            val language = settings.uiLanguage
            val locale = language.locale
            val month = currentMonth(context, id)
            val selected = selectedDay(context, id)
            val signature = "$month|$selected|${settings.widgetAlpha}|${settings.uiLanguage}|" +
                "${settings.widgetColoredTasks}|${settings.widgetCompletedStyle}|${settings.calendarWidgetTypography}|${LocalDate.now()}"
            val now = System.currentTimeMillis()
            if (!force && rendered[id] == signature) {
                if (now - (lastNotifiedAt[id] ?: 0L) < NOTIFY_THROTTLE_MS) return
                lastNotifiedAt[id] = now
                runCatching { manager.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid) }
                return
            }
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)

            val monthName = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                .replaceFirstChar { it.uppercase(locale) }
            CalendarWidgetText.set(views, R.id.widget_month_title, "$monthName, ${month.year}", 16f, settings.calendarWidgetTypography)
            val weekdayViews = intArrayOf(R.id.widget_weekday_mon, R.id.widget_weekday_tue, R.id.widget_weekday_wed,
                R.id.widget_weekday_thu, R.id.widget_weekday_fri, R.id.widget_weekday_sat, R.id.widget_weekday_sun)
            val weekdayLabels = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
            weekdayViews.forEachIndexed { index, viewId -> CalendarWidgetText.set(views, viewId, UiCatalog.translate(language, weekdayLabels[index]), 12f, settings.calendarWidgetTypography) }
            views.setContentDescription(R.id.widget_prev, UiCatalog.translate(language, "Предыдущий месяц"))
            views.setContentDescription(R.id.widget_next, UiCatalog.translate(language, "Следующий месяц"))
            views.setContentDescription(R.id.widget_menu_button, UiCatalog.translate(language, "Настройки виджета"))
            views.setContentDescription(R.id.widget_add, UiCatalog.translate(language, "Быстрая задача"))

            // Прозрачность фона — из настроек (выбирается в меню «⋮», этап 28).
            val alpha = settings.widgetAlpha
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
                data = android.net.Uri.parse("notessync://widget/$id")
            }
            views.setRemoteAdapter(R.id.calendar_grid, adapterIntent)

            // Шаблон интента: конкретная дата подставляется через fillInIntent ячейки.
            // NEW_TASK + пустой taskAffinity в манифесте: диалог живёт в своей задаче,
            // при закрытии под ним не всплывает MainActivity (этап 26).
            val template = PendingIntent.getActivity(
                context, id,
                Intent(context, QuickNoteActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.calendar_grid, template)
            val quick = QuickNoteActivity.intent(context, selected).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = android.net.Uri.parse("notels://widget/$id/quick-add")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            views.setOnClickPendingIntent(R.id.widget_add, PendingIntent.getActivity(context, id, quick,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            manager.updateAppWidget(id, views)
            rendered[id] = signature
            lastNotifiedAt[id] = now
            runCatching { manager.notifyAppWidgetViewDataChanged(id, R.id.calendar_grid) }
        }

        private val lastRefreshAt = java.util.concurrent.atomic.AtomicLong(0)
        private val rendered = ConcurrentHashMap<Int, String>()
        private val lastNotifiedAt = ConcurrentHashMap<Int, Long>()
        private val refreshPending = AtomicBoolean(false)
        private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "notels-widget-refresh").apply { isDaemon = true }
        }

        /**
         * Полная перерисовка всех виджетов. Аудит 2026-11-11: автосохранение
         * заметки дня вызывает refreshAll каждые 500 мс при наборе текста —
         * пересборка RemoteViews и запрос БД столько раз в секунду. Поэтому
         * неявные обновления (из автосохранения) идут не чаще REFRESH_THROTTLE_MS,
         * а явные действия (удаление, галочка задачи) — сразу, force = true.
         */
        fun refreshAll(context: Context, force: Boolean = true) {
            if (force) WeeklyWidgetUpdates.request(context)
            val app = context.applicationContext
            if (force) { renderAll(app, true); return }
            val now = System.currentTimeMillis()
            val previous = lastRefreshAt.get()
            if (now - previous < REFRESH_THROTTLE_MS) return
            if (!lastRefreshAt.compareAndSet(previous, now)) return
            // Автосохранение заметки больше не вызывает Binder к лаунчеру в UI-потоке.
            if (!refreshPending.compareAndSet(false, true)) return
            refreshExecutor.execute {
                try { renderAll(app, false) } catch (_: Throwable) { } finally { refreshPending.set(false) }
            }
        }

        private fun renderAll(context: Context, force: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(
                ComponentName(context, CalendarWidgetProvider::class.java)
            ).forEach { runCatching { updateWidget(context, manager, it, force) } }
        }

        private const val REFRESH_THROTTLE_MS = 2_000L
        private const val NOTIFY_THROTTLE_MS = 1_500L

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
