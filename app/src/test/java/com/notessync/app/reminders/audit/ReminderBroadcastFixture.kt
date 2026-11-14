package com.notessync.app.reminders.audit

import android.app.Application
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import com.notessync.app.reminders.ReminderReceiver
import org.junit.Assert.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.util.concurrent.TimeUnit

/** Wait for actual goAsync completion before observing notification shadows. */
internal fun deliverReminder(context: Context, queued: Intent) {
    val receiver = ReminderReceiver()
    val filter = IntentFilter(ReminderReceiver.ACTION_ALARM).apply { addDataScheme("notels") }
    if (android.os.Build.VERSION.SDK_INT < 33) {
        // Config.NONE omits the signature permission normally supplied by the merged AndroidX manifest.
        shadowOf(context.applicationContext as Application).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
        context.sendBroadcast(Intent(queued).setComponent(null))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(receiver).wentAsync())
        val pending = requireNotNull(shadowOf(receiver).originalPendingResult)
        Shadow.extract<ShadowBroadcastPendingResult>(pending).future.get(10, TimeUnit.SECONDS)
    } finally { context.unregisterReceiver(receiver) }
}
