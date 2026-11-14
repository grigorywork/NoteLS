package com.notessync.app.data.backup

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** At most one worker and one coalesced request. No idle coroutine, polling or per-event job queue. */
internal class AutoBackupQueue(private val scope: CoroutineScope,
    private val consume: suspend (Context, Boolean) -> Unit) {
    private data class Request(val context: Context, val afterNote: Boolean)
    private val pending = AtomicReference<Request?>(null)
    private val running = AtomicBoolean(false)

    fun request(context: Context, afterNote: Boolean) {
        pending.updateAndGet { Request(context, afterNote || it?.afterNote == true) }
        drain()
    }

    private fun drain() {
        if (!scope.isActive || !running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (isActive) {
                    val next = pending.getAndSet(null) ?: break
                    consume(next.context, next.afterNote)
                }
            } finally {
                running.set(false)
                // Cover an event arriving between the empty read and releasing worker ownership.
                if (pending.get() != null && scope.isActive) drain()
            }
        }
    }
}
