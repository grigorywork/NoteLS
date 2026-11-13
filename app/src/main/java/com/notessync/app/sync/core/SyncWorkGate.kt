package com.notessync.app.sync.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One DB exchange/restore at a time; manual work temporarily pauses automatic connections. */
internal class SyncTransferGate {
    private val mutex = Mutex()
    private val mutablePause = MutableStateFlow(0)
    val pauses = mutablePause.asStateFlow()
    fun pauseAutomatic(): Closeable {
        synchronized(this) { mutablePause.value++ }
        val closed = AtomicBoolean(false)
        return Closeable { if (closed.compareAndSet(false, true)) synchronized(this) { mutablePause.value-- } }
    }
    suspend fun <T> transfer(automatic: Boolean, block: suspend () -> T): T = mutex.withLock {
        if (automatic && pauses.value != 0) throw CancellationException("Automatic exchange paused")
        block()
    }
}

/** One shared gate per actual application process. Tests can model separate devices explicitly. */
internal object SyncWorkGate {
    val shared = SyncTransferGate()
    val pauses get() = shared.pauses
    fun pauseAutomatic() = shared.pauseAutomatic()
    fun releaseAfter(lease: Closeable?, idle: suspend () -> Unit) {
        if (lease == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { idle() } } finally { lease.close() }
        }
    }
    suspend fun <T> transfer(automatic: Boolean, block: suspend () -> T): T = shared.transfer(automatic, block)
}
