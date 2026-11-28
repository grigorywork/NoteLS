package com.notessync.app.tasks.running

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*

/** Explicit, non-exported, immutable-PI commands; URI binds identity, clock generation and verb. */
class RunningTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val control = read(intent) ?: return
        val pending = goAsync() ?: return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    RunningTaskActions.apply(app, control)
                    RunningTaskNotifications.refreshNow(app)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* No stale or partial fallback write. Next foreground reconciles. */ }
            finally { pending.finish() }
        }
    }
    companion object {
        internal const val ACTION = "com.notessync.app.RUNNING_TASK_CONTROL"
        internal fun intent(context: Context, control: RunningControl): Intent =
            Intent(context, RunningTaskReceiver::class.java).setAction(ACTION).setData(
                Uri.Builder().scheme("notels").authority("running-task").appendPath(control.id.toString())
                    .appendPath(control.syncId).appendPath(control.clock.name).appendPath(control.expectedAt.toString())
                    .appendPath(control.action.name).build())
        internal fun read(intent: Intent): RunningControl? {
            if (intent.action != ACTION) return null
            val data = intent.data ?: return null
            if (data.scheme != "notels" || data.authority != "running-task" || data.query != null || data.fragment != null) return null
            val parts = data.pathSegments
            if (parts.size != 5) return null
            val id = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val sync = parts[1].takeIf { it.length in 1..160 && it.isNotBlank() } ?: return null
            val at = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return runCatching { RunningControl(id, sync, RunningClock.valueOf(parts[2]), at, RunningAction.valueOf(parts[4])) }.getOrNull()
        }
    }
}
