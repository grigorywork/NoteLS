package com.notessync.app.sync.auto

import android.content.Context
import com.notessync.app.sync.trust.TrustIds
import java.util.UUID

internal object SyncIdentity {
    @Synchronized fun id(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val old = prefs.getString("device_id", null)
        if (old != null && TrustIds.valid(old)) return old
        return UUID.randomUUID().toString().also { check(prefs.edit().putString("device_id", it).commit()) }
    }
    @Synchronized fun reset(context: Context) {
        check(context.applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE).edit().clear().commit())
    }
}
