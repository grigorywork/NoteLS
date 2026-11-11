package com.notessync.app.sync

import com.notessync.app.sync.core.SyncResources
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncResourcesTest {
    @Test fun `close interrupts a blocked accept`() {
        val owned = SyncResources()
        val server = owned.own(ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        val executor = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        try {
            val done = executor.submit<Boolean> {
                ready.countDown()
                try { server.accept().close(); false } catch (_: java.net.SocketException) { true }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            owned.close()
            assertTrue(done.get(2, TimeUnit.SECONDS))
        } finally { owned.close(); executor.shutdownNow() }
    }

    @Test fun `close interrupts reading an established connection`() {
        val owned = SyncResources()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
                val accepted = owned.own(server.accept())
                val ready = CountDownLatch(1)
                try {
                    val done = executor.submit<Boolean> {
                        ready.countDown()
                        try { accepted.getInputStream().read(); false }
                        catch (_: java.net.SocketException) { true }
                    }
                    assertTrue(ready.await(2, TimeUnit.SECONDS))
                    owned.close()
                    assertTrue(done.get(2, TimeUnit.SECONDS))
                    assertTrue(accepted.isClosed)
                } finally { owned.close(); executor.shutdownNow() }
            }
        }
    }

    @Test fun `resource arriving after cancellation is immediately closed`() {
        val owned = SyncResources()
        owned.close()
        val socket = Socket()
        try { owned.own(socket); fail("Cancelled session accepted a socket") }
        catch (_: CancellationException) { assertTrue(socket.isClosed) }
    }

    @Test fun `closing is idempotent and one failure does not leak others`() {
        val owned = SyncResources()
        var closed = 0
        owned.own(Closeable { closed++ })
        owned.own(Closeable { throw java.io.IOException("test") })
        owned.close()
        owned.close()
        assertEquals(1, closed)
        // Old sessions never own resources of the next session.
        val next = SyncResources()
        val socket = next.own(Socket())
        owned.close()
        assertFalse(socket.isClosed)
        next.close()
        assertTrue(socket.isClosed)
    }
}
