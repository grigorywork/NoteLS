package com.notessync.app.settings

import com.notessync.app.i18n.uiText

/** Discrete, bounded values: no untrusted float can make a widget unreadable. */
enum class CalendarTextSize(val scale: Float, private val title: String) {
    COMPACT(0.85f, "Компактный"), NORMAL(1f, "Обычный"), LARGE(1.15f, "Крупный");
    val label: String get() = uiText(title)
}

/** Family names survive TextUtils/RemoteViews parceling; bundled Compose fonts do not. */
enum class CalendarWidgetFont(val family: String?, private val title: String) {
    DEFAULT(null, "Стандартный"), SANS("sans-serif", "Гротеск"),
    SERIF("serif", "С засечками"), MONO("monospace", "Моноширинный"),
    CURSIVE("cursive", "Рукописный");
    val label: String get() = uiText(title)
}

data class CalendarWidgetTypography(
    val font: CalendarWidgetFont = CalendarWidgetFont.DEFAULT,
    val size: CalendarTextSize = CalendarTextSize.NORMAL
)
