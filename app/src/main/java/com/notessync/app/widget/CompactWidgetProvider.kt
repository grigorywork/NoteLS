package com.notessync.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

class CompactWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CompactWidgetUpdates.request(context) { pending?.finish() }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val pending = goAsync()
        CompactWidgetUpdates.request(context) { pending?.finish() }
    }
    override fun onEnabled(context: Context) { CompactWidgetUpdates.startIfPresent(context) }
    override fun onDeleted(context: Context, ids: IntArray) { CompactWidgetUpdates.startIfPresent(context) }
    override fun onDisabled(context: Context) { CompactWidgetUpdates.stop() }
}
