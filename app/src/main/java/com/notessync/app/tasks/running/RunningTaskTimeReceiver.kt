package com.notessync.app.tasks.running

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Wall-clock changes rebase SystemUI once; no repeating time broadcast or new scheduled work. */
class RunningTaskTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) { RunningTaskNotifications.rebase(context.applicationContext); com.notessync.app.data.subtasks.SubtaskAlarms.reconcile(context.applicationContext) } }
            catch (_: Exception) { /* Real foreground entry retries. */ }
            finally { pending.finish() }
        }
    }
}
