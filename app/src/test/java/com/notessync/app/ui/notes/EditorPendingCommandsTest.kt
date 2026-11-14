package com.notessync.app.ui.notes

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EditorPendingCommandsTest {
    @Test fun `last completion between size and iterator cannot break the snapshot`() = runBlocking {
        val job = Job(); val backing = ConcurrentHashMap.newKeySet<Job>().apply { add(job) }
        job.invokeOnCompletion { backing.remove(job) }
        val observed = AtomicBoolean(false)
        val racing = object : AbstractSet<Job>() {
            override fun isEmpty() = backing.isEmpty()
            override fun iterator(): Iterator<Job> = backing.iterator()
            override val size: Int get() {
                val before = backing.size
                if (observed.compareAndSet(false, true)) job.complete()
                return before
            }
        }
        racing.awaitPendingCommands()
        assertTrue(observed.get()); assertTrue(job.isCompleted); assertTrue(backing.isEmpty())
    }
    @Test fun `cancelling accepted job is joined until its non cancellable commit finishes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>(); val pending = ConcurrentHashMap.newKeySet<Job>()
        val work = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { release.await() } }
        pending.add(work); work.invokeOnCompletion { pending.remove(work) }; scope.cancel()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { pending.awaitPendingCommands() }
        try { assertTrue(work.isCancelled); assertFalse(work.isCompleted); assertFalse(waiter.isCompleted) }
        finally { release.complete(Unit); waiter.await() }
        assertTrue(work.isCompleted); assertTrue(pending.isEmpty())
    }
    @Test fun `concurrent completions repeatedly leave no unfinished jobs`() = runBlocking {
        withTimeout(30000) {
            repeat(10000) {
                val job = Job(); val pending = ConcurrentHashMap.newKeySet<Job>().apply { add(job) }
                job.invokeOnCompletion { pending.remove(job) }
                val completion = launch(Dispatchers.Default) { job.complete() }
                pending.awaitPendingCommands(); completion.join()
                assertTrue(job.isCompleted); assertTrue(pending.isEmpty())
            }
        }
    }
}
