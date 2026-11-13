package com.notessync.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import com.notessync.app.MainActivity
import com.notessync.app.R
import com.notessync.app.data.local.model.CompactWidgetEntry
import com.notessync.app.i18n.UiCatalog
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.notes.text.notePreviewText
import com.notessync.app.settings.SettingsState
import com.notessync.app.settings.WidgetCompletedStyle
import kotlin.math.roundToInt

internal data class CompactWidgetPreferences(val language: UiLanguage, val alpha: Float,
    val coloredTasks: Boolean, val completed: WidgetCompletedStyle) {
    companion object {
        fun from(state: SettingsState) = CompactWidgetPreferences(state.uiLanguage,
            state.widgetAlpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.85f,
            state.widgetColoredTasks, state.widgetCompletedStyle)
    }
}

internal object CompactWidgetViews {
    private val whitespace = Regex("\\s+")
    val rows = intArrayOf(R.id.compact_row_0, R.id.compact_row_1, R.id.compact_row_2)
    fun build(context: Context, id: Int, entries: List<CompactWidgetEntry>, settings: CompactWidgetPreferences, failed: Boolean = false): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact)
        fun text(key: String) = UiCatalog.translate(settings.language, key)
        views.setInt(R.id.compact_bg, "setImageAlpha", (settings.alpha * 255).roundToInt())
        val home = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .setData(Uri.parse("notels://compact/$id/home"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        views.setOnClickPendingIntent(R.id.compact_title, activity(context, id, home))
        views.setContentDescription(R.id.compact_title, text("Открыть NoteLS"))
        // Missing dayKey is evaluated by QuickTaskViewModel when tapped, including after midnight.
        val add = Intent(context, QuickNoteActivity::class.java).putExtra(QuickNoteActivity.EXTRA_QUICK_ADD, true)
            .setData(Uri.parse("notels://compact/$id/add")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(R.id.compact_add, activity(context, id, add))
        views.setContentDescription(R.id.compact_add, text("Быстрая задача"))
        val menu = Intent(context, WidgetSettingsActivity::class.java).setData(Uri.parse("notels://compact/$id/settings"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(R.id.compact_menu, activity(context, id, menu))
        views.setContentDescription(R.id.compact_menu, text("Настройки виджета"))
        rows.forEachIndexed { index, viewId ->
            val entry = entries.getOrNull(index)
            if (entry == null || failed) {
                views.setTextViewText(viewId, "")
                views.setContentDescription(viewId, "")
                views.setOnClickPendingIntent(viewId, null)
                views.setViewVisibility(viewId, if (entries.isEmpty() || failed) View.GONE else View.INVISIBLE)
            } else {
                val title = notePreviewText(entry.title.ifBlank { entry.content }.replace(whitespace, " ").trim()
                    .ifBlank { text("Без названия") }, 120)
                val prefix = (if (entry.isPinned) "★ " else "") + if (entry.isTask) (if (entry.isCompleted) "✓ " else "○ ") else ""
                val rendered = SpannableStringBuilder(prefix + title)
                val color = when {
                    entry.isTask && entry.isCompleted && settings.completed.usesGreen -> 0xFF81C784.toInt()
                    entry.isTask && settings.coloredTasks -> CalendarFactory.widgetTaskColor(entry.taskColor)
                    !entry.isTask && entry.noteColor.isNotBlank() -> CalendarFactory.widgetTaskColor(entry.noteColor)
                    else -> Color.WHITE
                }
                rendered.setSpan(ForegroundColorSpan(color), 0, rendered.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (entry.isTask && entry.isCompleted && settings.completed.usesStrike) {
                    rendered.setSpan(StrikethroughSpan(), prefix.length, rendered.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                views.setTextViewText(viewId, rendered)
                views.setViewVisibility(viewId, View.VISIBLE)
                views.setContentDescription(viewId, UiCatalog.translate(settings.language, "Открыть «%1\$s»", arrayOf(title)))
                views.setOnClickPendingIntent(viewId, activity(context, id, WidgetNoteLinks.intent(context, id, entry.id, entry.syncId)))
            }
        }
        views.setTextViewText(R.id.compact_empty, text(if (failed) "Не удалось обновить записи. Откройте NoteLS." else "Пока нет записей"))
        views.setViewVisibility(R.id.compact_empty, if (entries.isEmpty() || failed) View.VISIBLE else View.GONE)
        return views
    }
    private fun activity(context: Context, id: Int, intent: Intent) = PendingIntent.getActivity(context, id, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
