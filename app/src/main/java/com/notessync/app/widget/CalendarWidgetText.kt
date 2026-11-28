package com.notessync.app.widget

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.widget.RemoteViews
import com.notessync.app.settings.CalendarWidgetTypography

/** Keep existing color/strike/privacy text; only platform Parcelable spans cross the IPC boundary. */
internal object CalendarWidgetText {
    fun styled(text: CharSequence, typography: CalendarWidgetTypography): CharSequence {
        val family = typography.font.family ?: return text
        return SpannableStringBuilder(text).apply {
            if (isNotEmpty()) setSpan(TypefaceSpan(family), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun set(views: RemoteViews, id: Int, text: CharSequence, baseSp: Float, typography: CalendarWidgetTypography) {
        views.setTextViewText(id, styled(text, typography))
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, baseSp * typography.size.scale)
    }
}
