package com.notessync.app.sync

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.sync.android.*
import com.notessync.app.sync.core.*
import com.notessync.app.sync.crypto.PairingHandshake
import com.notessync.app.sync.crypto.PairingSecret
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.qr.SyncQr
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SyncControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val initialized = AtomicInteger()
    private val connected = AtomicInteger()
    private val accepted = AtomicInteger()
    private val data = object : SyncDataSource by FakeSyncDataSource() {
        override suspend fun ensureSyncIds() { initialized.incrementAndGet() }
    }
    private val connections = object : SyncConnections {
        override fun localAddress() = "192.168.1.2"
        override fun now() = dispatcher.scheduler.currentTime
        override fun listen(address: String, port: Int): ServerSocket =
            object : ServerSocket(0, 1, InetAddress.getLoopbackAddress()) {
                override fun accept(): Socket = super.accept().also { accepted.incrementAndGet() }
            }.also { servers.add(it) }
        override fun socket(): Socket { connected.incrementAndGet(); return Socket() }
    }
    private lateinit var vm: SyncViewModel
    @Before fun before() { Dispatchers.setMain(dispatcher); vm = SyncViewModel(app, data, connections); models.put("sync", vm) }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); servers.forEach { it.close() }; Dispatchers.resetMain() }
    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(8_000) { while (!condition()) { dispatcher.scheduler.runCurrent(); delay(5) } }
    }
    private fun start(): ServerSocket {
        val count = servers.size
        vm.startWaiting(); dispatcher.scheduler.runCurrent(); await { servers.size > count }
        return servers.last()
    }
    @Test fun `invalid manual and QR inputs never open a network socket or access data`() {
        vm.connect("example.com", PairingSecret.generate()); vm.connect("192.168.1.2", "ABCD2345")
        vm.connectFromQr("notessync://connect?ip=127.0.0.1")
        dispatcher.scheduler.runCurrent(); assertEquals(SyncMode.ERROR, vm.uiState.value.mode)
        assertEquals(0, initialized.get()); assertEquals(0, connected.get()); assertTrue(servers.isEmpty())
        // AndroidViewModelFactory still has its public one-argument constructor.
        assertNotNull(SyncViewModel::class.java.getConstructor(Application::class.java))
    }
    @Test fun `host invitation actually expires and cancellation clears secret even during a silent socket`() {
        val server = start(); assertTrue(PairingSecret.valid(vm.uiState.value.sessionCode))
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
            await { accepted.get() == 1 }
            dispatcher.scheduler.advanceTimeBy(SyncQr.TTL_MS); dispatcher.scheduler.runCurrent()
            await { server.isClosed }
            assertEquals(SyncMode.ERROR, vm.uiState.value.mode); assertEquals("", vm.uiState.value.sessionCode)
            assertEquals("", vm.uiState.value.qrPayload); assertEquals(0, initialized.get())
        }
    }
    @Test fun `bad anonymous connection does not consume invitation and next authenticated peer syncs`() {
        val server = start(); val key = vm.uiState.value.sessionCode
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
            it.getOutputStream().write("invalid!".toByteArray()); it.getOutputStream().flush()
        }
        assertEquals(0, initialized.get()); assertEquals(SyncMode.WAITING, vm.uiState.value.mode)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val peer = executor.submit<Boolean> {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                    socket.soTimeout = 5_000
                    val crypto = PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), key, true)
                    runBlocking { SyncSession(FrameTransport(socket.getInputStream(), socket.getOutputStream(), crypto),
                        FakeSyncDataSource(), "peer", "Peer", { 0 }).runAsClient() }
                }
                true
            }
            await { peer.isDone }; assertTrue(peer.get(1, TimeUnit.SECONDS))
            await { vm.uiState.value.mode == SyncMode.DONE }
            assertEquals(1, initialized.get()); assertEquals("", vm.uiState.value.sessionCode); assertTrue(server.isClosed)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(8, TimeUnit.SECONDS)) }
    }
    @Test fun `late cancelled worker cannot overwrite a new form or re-expose its invitation`() {
        val server = start()
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
            vm.cancel(); vm.showClientForm(); dispatcher.scheduler.runCurrent(); await { server.isClosed && vm.viewModelScope.coroutineContext[Job]!!.children.none() }
            assertEquals(SyncMode.CLIENT_FORM, vm.uiState.value.mode)
            assertEquals("", vm.uiState.value.qrPayload); assertEquals("", vm.uiState.value.sessionCode)
            assertEquals(0, initialized.get())
        }
    }
}
