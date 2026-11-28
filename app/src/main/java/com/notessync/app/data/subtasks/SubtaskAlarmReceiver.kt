package com.notessync.app.data.subtasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class SubtaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SubtaskAlarms.ACTION || intent.data != SubtaskAlarms.uri) return
        val pending = goAsync() ?: return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) { SubtaskAlarms.deliver(app) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Durable recovery retries on a real foreground/boot, never a polling loop. */ }
            finally { pending.finish() }
        }
    }
}
