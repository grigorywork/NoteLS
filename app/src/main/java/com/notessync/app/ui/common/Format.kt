package com.notessync.app.ui.common

import com.notessync.app.i18n.uiText

import java.text.DateFormat
import java.util.Date

fun formatDateTime(timestamp: Long): String =
    if (timestamp == 0L) uiText("Никогда")
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, com.notessync.app.i18n.UiStrings.locale).format(Date(timestamp))

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> uiText("%.1f МБ").format(com.notessync.app.i18n.UiStrings.locale, bytes / 1_048_576f)
    bytes >= 1024 -> uiText("%.1f КБ").format(com.notessync.app.i18n.UiStrings.locale, bytes / 1024f)
    else -> uiText("%1\$s Б", (bytes))
}
