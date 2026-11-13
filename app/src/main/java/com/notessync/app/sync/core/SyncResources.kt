package com.notessync.app.sync.core

import java.io.Closeable
import kotlinx.coroutines.CancellationException

/** Per-session ownership: cancellation also interrupts blocking accept/connect/read. */
internal class SyncResources : Closeable {
    private val lock = Any()
    private var closed = false
    private val resources = mutableListOf<Closeable>()

    fun <T : Closeable> own(resource: T): T = synchronized(lock) {
        if (closed) {
            runCatching { resource.close() }
            throw CancellationException("Sync session closed")
        }
        resources.add(resource)
        resource
    }

    fun release(resource: Closeable) {
        synchronized(lock) { resources.remove(resource) }
        runCatching { resource.close() }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        pending.asReversed().forEach { runCatching { it.close() } }
    }
}
