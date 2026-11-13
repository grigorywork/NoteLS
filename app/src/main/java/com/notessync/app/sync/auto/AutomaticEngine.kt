package com.notessync.app.sync.auto

import com.notessync.app.sync.core.*
import com.notessync.app.sync.crypto.PairingHandshake
import com.notessync.app.sync.crypto.SessionCrypto
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.trust.*
import com.notessync.app.sync.qr.SyncQr
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

internal enum class AutoPhase { LOADING, PAUSED, DISABLED, PAIR_FIRST, NO_WIFI, WAITING, SYNCING, SUCCESS, ERROR, UNAVAILABLE }
internal data class AutoStatus(val phase: AutoPhase = AutoPhase.PAIR_FIRST, val peer: String = "")

/** Foreground owner; every resource belongs to this network generation. Discovery is not trust. */
@OptIn(FlowPreview::class)
internal class AutomaticEngine(
    parent: CoroutineScope,
    private val network: AutoNetwork,
    private val peers: TrustedPeers,
    private val data: SyncDataSource,
    private val ownId: String,
    private val deviceName: String,
    changes: Flow<Unit>,
    private val report: (AutoStatus) -> Unit,
    private val workGate: SyncTransferGate = SyncWorkGate.shared
) : Closeable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.IO)
    private val resources = SyncResources()
    private val serial = Mutex()
    private val active = AtomicReference<Attempt?>()
    private val endpoints = ConcurrentHashMap<String, PeerEndpoint>()
    private val pendingLock = Any()
    private val pending = mutableMapOf<String, Request>()
    private val changeSignal = changes
    private class Request(var again: Boolean = true)
    private data class Attempt(val id: String, val revision: String, val client: Boolean, val socket: Socket, val result: Deferred<Unit>)

    fun start() {
        require(TrustIds.valid(ownId))
        try {
            val server = resources.own(network.listen())
            guarded {
                var failures = 0
                while (isActive) {
                    val socket = try { resources.own(server.accept()) } catch (_: Exception) {
                        if (isActive) report(AutoStatus(AutoPhase.ERROR))
                        break
                    }
                    try {
                        socket.soTimeout = 5_000
                        val reader = DataInputStream(socket.getInputStream())
                        require(ByteArray(MAGIC.size).also(reader::readFully).contentEquals(MAGIC))
                        val id = ByteArray(36).also(reader::readFully).toString(Charsets.US_ASCII)
                        require(TrustIds.valid(id) && id != ownId)
                        val peer = peers.get(id) ?: error("Unknown peer")
                        check(peers.current(id, peer.revision))
                        val crypto = PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), peer.secret, false)
                        val previous = active.get()
                        // Simultaneous dialing has a deterministic winner, without waiting on two DB locks.
                        if (previous?.client == true && previous.id == id && id < ownId) {
                            previous.result.cancel(); previous.socket.close(); previous.result.join()
                        }
                        if (!serial.tryLock()) { resources.release(socket); continue }
                        launchAttempt(socket, peer, client = false, crypto = crypto)
                        failures = 0
                    } catch (cancel: CancellationException) { resources.release(socket); throw cancel }
                    catch (_: Exception) {
                        resources.release(socket)
                        if (++failures >= 8) { failures = 0; delay(10_000) }
                    }
                }
            }
            resources.own(network.discover(ownId, peers.state.value.peers.map { it.id }.toSet(), ::found, ::lost))
            guarded {
                peers.state.map { it.available to it.peers.map { peer -> peer.id to peer.revision } }.distinctUntilChanged().collect {
                    active.get()?.takeIf { attempt -> !peers.current(attempt.id, attempt.revision) }?.let { attempt ->
                        attempt.result.cancel(); attempt.socket.close()
                    }
                    endpoints.keys.filter { id -> peers.state.value.peers.none { it.id == id } }.forEach { endpoints.remove(it) }
                }
            }
            guarded { changeSignal.debounce(1_500).collect { requestAll() } }
            report(AutoStatus(AutoPhase.WAITING))
            guarded {
                peers.state.value.peers.forEach { summary ->
                    val peer = peers.get(summary.id)
                    if (peer != null && peer.host.isNotEmpty() && peer.host != network.address) found(PeerEndpoint(peer.id, peer.host))
                }
            }
        } catch (error: Exception) { close(); throw error }
    }
    private fun found(endpoint: PeerEndpoint) {
        if (!scope.isActive || endpoint.id == ownId || !SyncQr.localAddress(endpoint.host) || endpoint.port != AUTO_PORT) return
        if (peers.state.value.peers.none { it.id == endpoint.id }) return
        if (endpoints.put(endpoint.id, endpoint) != endpoint) request(endpoint.id)
    }
    private fun lost(id: String) {
        endpoints.remove(id)
        active.get()?.takeIf { it.id == id }?.let { it.result.cancel(); it.socket.close() }
    }
    fun requestAll() { endpoints.keys.forEach(::request) }
    private fun request(id: String) {
        if (!scope.isActive) return
        val request = synchronized(pendingLock) {
            val existing = pending[id]
            if (existing != null) { existing.again = true; return }
            Request().also { pending[id] = it }
        }
        guarded {
            try {
                while (isActive) {
                    synchronized(pendingLock) { request.again = false }
                    for (attempt in 0..2) {
                        if (attempt > 0) delay(if (attempt == 1) 2_000 else 5_000)
                        val endpoint = endpoints[id] ?: break
                        val peer = peers.get(id) ?: break
                        if (!peers.current(id, peer.revision)) break
                        try {
                            serial.lock()
                            val socket = try {
                                currentCoroutineContext().ensureActive()
                                check(endpoints[id] == endpoint && peers.current(id, peer.revision))
                                resources.own(network.socket())
                            } catch (error: Exception) { serial.unlock(); throw error }
                            launchAttempt(socket, peer, client = true, endpoint = endpoint).await()
                            break
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { if (attempt == 2 && scope.isActive) report(AutoStatus(AutoPhase.ERROR, peer.name)) }
                    }
                    val repeat = synchronized(pendingLock) {
                        if (request.again) true else { pending.remove(id, request); false }
                    }
                    if (!repeat) break
                    delay(750)
                }
            } finally { synchronized(pendingLock) { pending.remove(id, request) } }
        }
    }
    /** serial is already held. Completion also releases it if a child is cancelled before starting. */
    private fun launchAttempt(socket: Socket, peer: TrustedPeer, client: Boolean, endpoint: PeerEndpoint? = null, crypto: SessionCrypto? = null): Deferred<Unit> {
        val result = scope.async(start = CoroutineStart.LAZY) {
            var secure = crypto
            if (client) {
                socket.connect(InetSocketAddress(endpoint!!.host, endpoint.port), 5_000)
                socket.soTimeout = 5_000
                DataOutputStream(socket.getOutputStream()).apply { write(MAGIC); write(ownId.toByteArray(Charsets.US_ASCII)); flush() }
                secure = PairingHandshake.establish(socket.getInputStream(), socket.getOutputStream(), peer.secret, true)
            }
            currentCoroutineContext().ensureActive()
            check(peers.current(peer.id, peer.revision))
            socket.soTimeout = 120_000
            val started = System.currentTimeMillis()
            workGate.transfer(automatic = true) {
                check(peers.current(peer.id, peer.revision))
                report(AutoStatus(AutoPhase.SYNCING, peer.name))
                val context = currentCoroutineContext()
                val session = SyncSession(FrameTransport(socket.getInputStream(), socket.getOutputStream(), secure!!), data,
                    ownId, deviceName.take(128), { Long.MIN_VALUE },
                    onProgress = { context.ensureActive(); check(peers.current(peer.id, peer.revision)) },
                    expectedPeerId = peer.id, trustedPairing = true,
                    onPeerValidated = { check(peers.current(peer.id, peer.revision)) })
                if (client) session.runAsClient() else session.runAsServer()
                currentCoroutineContext().ensureActive()
                peers.synced(peer.id, peer.revision, started, socket.inetAddress.hostAddress.orEmpty())
            }
            if (scope.isActive) report(AutoStatus(AutoPhase.SUCCESS, peer.name))
        }
        val attempt = Attempt(peer.id, peer.revision, client, socket, result)
        active.set(attempt)
        val timeout = guarded { delay(10 * 60_000L); runCatching { socket.close() }; result.cancel() }
        result.invokeOnCompletion {
            timeout.cancel(); active.compareAndSet(attempt, null); resources.release(socket); serial.unlock()
            if (it != null && it !is CancellationException && scope.isActive) report(AutoStatus(AutoPhase.ERROR, peer.name))
        }
        result.start()
        return result
    }
    private fun guarded(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (isActive) report(AutoStatus(AutoPhase.ERROR)) }
    }
    override fun close() { scope.cancel(); resources.close(); endpoints.clear(); synchronized(pendingLock) { pending.clear() } }
    suspend fun awaitStopped() { job.join() }
    companion object { private val MAGIC = "NoteLSA1".toByteArray(Charsets.US_ASCII) }
}
