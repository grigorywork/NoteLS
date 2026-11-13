package com.notessync.app.sync

import com.notessync.app.sync.auto.*
import com.notessync.app.sync.core.*
import com.notessync.app.sync.protocol.*
import com.notessync.app.sync.trust.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class MemoryPeers(initial: List<TrustedPeer>) : TrustedPeers {
    private val records = ConcurrentHashMap(initial.associateBy { it.id })
    private val mutable = MutableStateFlow(TrustState(true, true, initial.map { it.summary() }))
    override val state = mutable.asStateFlow()
    override suspend fun load() = Unit
    override suspend fun get(id: String) = records[id]
    override suspend fun pair(id: String, name: String, secret: String, host: String, watermark: Long) {
        records[id] = TrustedPeer(id, name, secret, watermark, host, UUID.randomUUID().toString()); publish()
    }
    override suspend fun synced(id: String, revision: String, watermark: Long, host: String) {
        records.computeIfPresent(id) { _, old -> if (old.revision == revision) old.copy(lastSyncAt = watermark) else old }; publish()
    }
    override suspend fun forget(id: String) { records.remove(id); publish() }
    override suspend fun reset() { records.clear(); publish() }
    private fun publish() { mutable.value = TrustState(true, true, records.values.map { it.summary() }) }
}

class AutomaticEngineTest {
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val secret = "ab".repeat(32)
    private class Store : SyncDataSource {
        private val mutex = Mutex(); private val data = FakeSyncDataSource()
        val accesses = AtomicInteger()
        suspend fun put(note: NoteDto, files: Map<String, ByteArray> = emptyMap()) = mutex.withLock { data.putLocal(note, files) }
        suspend fun snapshot() = mutex.withLock { data.notes.toMap() }
        suspend fun bytes(id: String) = mutex.withLock { data.files[id]?.copyOf() }
        override suspend fun ensureSyncIds() { currentCoroutineContext().ensureActive(); accesses.incrementAndGet() }
        override suspend fun getChangesSince(since: Long) = mutex.withLock { currentCoroutineContext().ensureActive(); data.getChangesSince(since) }
        override suspend fun applyChanges(changes: ChangeSet) = mutex.withLock { currentCoroutineContext().ensureActive(); data.applyChanges(changes) }
        override suspend fun hasAttachmentBytes(attachmentSyncId: String) = mutex.withLock { data.hasAttachmentBytes(attachmentSyncId) }
        override suspend fun readAttachmentBytes(attachmentSyncId: String) = mutex.withLock { data.readAttachmentBytes(attachmentSyncId) }
        override suspend fun writeAttachmentBytes(meta: AttachmentDto, data: ByteArray) = mutex.withLock { currentCoroutineContext().ensureActive(); this.data.writeAttachmentBytes(meta, data) }
        override suspend fun markSynced(changeSet: ChangeSet) = mutex.withLock { data.markSynced(changeSet) }
    }
    private class Bus(simultaneous: Boolean) {
        val listeners = ConcurrentHashMap<String, ServerSocket>()
        private data class Registration(val endpoint: PeerEndpoint, val allowed: Set<String>, val found: (PeerEndpoint) -> Unit, val lost: (String) -> Unit)
        private val registrations = mutableMapOf<String, Registration>()
        private val barrier = if (simultaneous) CyclicBarrier(2) else null
        private val dials = AtomicInteger()
        fun dial() { if (dials.incrementAndGet() <= 2) barrier?.await(5, TimeUnit.SECONDS) }
        @Synchronized fun register(id: String, address: String, allowed: Set<String>, found: (PeerEndpoint) -> Unit, lost: (String) -> Unit): Closeable {
            val registration = Registration(PeerEndpoint(id, address), allowed, found, lost)
            registrations.values.forEach {
                if (id in it.allowed) it.found(registration.endpoint)
                if (it.endpoint.id in allowed) found(it.endpoint)
            }
            registrations[id] = registration
            return Closeable { synchronized(this) { registrations.remove(id); registrations.values.forEach { if (id in it.allowed) it.lost(id) } } }
        }
    }
    private class Lan(private val bus: Bus, override val address: String) : AutoNetwork {
        var server: ServerSocket? = null
        val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        override fun listen(): ServerSocket = ServerSocket(0, 4, InetAddress.getLoopbackAddress()).also { server = it; bus.listeners[address] = it }
        override fun socket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                bus.dial()
                val target = bus.listeners[(endpoint as InetSocketAddress).hostString] ?: throw ConnectException("Fixture peer unavailable")
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), target.localPort), timeout)
            }
        }.also { sockets.add(it) }
        override fun discover(ownId: String, allowed: Set<String>, found: (PeerEndpoint) -> Unit, lost: (String) -> Unit) = bus.register(ownId, address, allowed, found, lost)
    }
    private suspend fun await(predicate: suspend () -> Boolean) { withTimeout(12_000) { while (!predicate()) delay(10) } }
    private fun note(id: String, title: String, time: Long) = NoteDto(id, title, "Body $title", false, false, 0, time)
    private fun peers(id: String, key: String = secret) = MemoryPeers(listOf(TrustedPeer(id, "Device", key, revision = UUID.randomUUID().toString())))

    @Test fun `simultaneous trusted dialing converges and later edits sync without another pairing or polling`() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = Bus(true); val left = Lan(bus, "192.168.1.2"); val right = Lan(bus, "192.168.1.3")
        val dataA = Store(); val dataB = Store(); val changesA = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val changesB = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val id = UUID.randomUUID().toString(); val file = ByteArray(70_003) { it.toByte() }
        val withFile = note("a", "A", 100).copy(attachments = listOf(AttachmentDto(id, "a", "audio/mp4", file.size.toLong(), Hashing.sha256Hex(file))))
        dataA.put(withFile, mapOf(id to file)); dataB.put(note("b", "B", 200))
        val engineA = AutomaticEngine(parent, left, peers(b), dataA, a, "A", changesA, {}, workGate = SyncTransferGate())
        val engineB = AutomaticEngine(parent, right, peers(a), dataB, b, "B", changesB, {}, workGate = SyncTransferGate())
        try {
            engineA.start(); engineB.start()
            await { dataA.snapshot().size == 2 && dataB.snapshot().size == 2 && dataB.bytes(id) != null }
            assertArrayEquals(file, dataB.bytes(id))
            dataB.put(note("later", "From B", 300)); changesB.emit(Unit)
            await { dataA.snapshot()["later"]?.title == "From B" }
            dataA.put(note("new-a", "From A", 400)); changesA.emit(Unit)
            await { dataB.snapshot()["new-a"]?.title == "From A" }
        } finally { engineA.close(); engineB.close(); engineA.awaitStopped(); engineB.awaitStopped(); parent.cancel() }
        assertTrue(left.server!!.isClosed && right.server!!.isClosed)
        assertTrue(left.sockets.all { it.isClosed } && right.sockets.all { it.isClosed })
    }
    @Test fun `a discovery match with the wrong stored key cannot access either database`() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default); val bus = Bus(false)
        val left = Lan(bus, "192.168.1.2"); val right = Lan(bus, "192.168.1.3"); val dataA = Store(); val dataB = Store()
        val engineA = AutomaticEngine(parent, left, peers(b), dataA, a, "A", emptyFlow(), {}, workGate = SyncTransferGate())
        val engineB = AutomaticEngine(parent, right, peers(a, "cd".repeat(32)), dataB, b, "B", emptyFlow(), {}, workGate = SyncTransferGate())
        try { engineA.start(); engineB.start(); await { left.sockets.isNotEmpty() && right.sockets.isNotEmpty() }; delay(250)
            assertEquals(0, dataA.accesses.get()); assertEquals(0, dataB.accesses.get())
        } finally { engineA.close(); engineB.close(); engineA.awaitStopped(); engineB.awaitStopped(); parent.cancel() }
    }
    @Test fun `closed foreground generation stops accepting and changing local data does not reopen sockets`() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default); val bus = Bus(false); val lan = Lan(bus, "192.168.1.2")
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4); val data = Store()
        val engine = AutomaticEngine(parent, lan, peers(b), data, a, "A", changes, {}, workGate = SyncTransferGate())
        engine.start(); engine.close(); engine.awaitStopped()
        data.put(note("local", "Later", 100)); changes.emit(Unit); delay(30)
        assertTrue(lan.server!!.isClosed); assertTrue(lan.sockets.isEmpty()); assertEquals(0, data.accesses.get()); parent.cancel()
    }
}
