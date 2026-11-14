package com.notessync.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Rebuild future reminders and countdowns after boot or an application update, including legacy PI migration. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync() ?: return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) { AlarmReconciler.reschedule(app) } }
            catch (_: Exception) { /* Interrupted recovery keeps a cursor for the next foreground session. */ }
            finally { pending.finish() }
        }
    }
}
