package com.notessync.app.ui.notes

import kotlinx.coroutines.Job

/** Completion callbacks may remove the last job between observing size and traversing the set. */
internal suspend fun Set<Job>.awaitPendingCommands() {
    while (isNotEmpty()) {
        // ConcurrentHashMap's array snapshot tolerates shrinkage; Kotlin toList's singleton fast path does not.
        for (job in toTypedArray()) job.join()
    }
}
